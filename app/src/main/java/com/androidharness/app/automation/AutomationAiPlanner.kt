package com.androidharness.app.automation

import com.androidharness.app.AppContainer
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.ProviderFactory
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class AutomationAiTurn(
    val role: Role,
    val text: String,
)

data class AutomationAiDraft(
    val title: String,
    val prompt: String,
    val checkCommand: String,
    val schedule: AutomationSchedule,
    val scheduledAt: Long?,
    val hour: Int,
    val minute: Int,
)

sealed interface AutomationAiReply {
    data class Question(val message: String) : AutomationAiReply
    data class Draft(val message: String, val automation: AutomationAiDraft) : AutomationAiReply
}

class AutomationAiPlanner(private val c: AppContainer) {
    suspend fun reply(projectName: String, turns: List<AutomationAiTurn>): AutomationAiReply {
        require(turns.isNotEmpty()) { "Tell AI what you want to automate." }

        val settings = c.settings.settings.first()
        val providerId = if (settings.planningModelsEnabled) {
            settings.executionProviderId ?: settings.activeProviderId
        } else {
            settings.activeProviderId
        }
        val provider = c.providers.providers.first().firstOrNull { it.id == providerId }
            ?: error("Choose an AI provider in Settings first.")
        val model = (if (settings.planningModelsEnabled) settings.executionModel else settings.activeModel)
            ?.takeIf { it.isNotBlank() } ?: provider.model
        val apiKey = c.providers.apiKey(provider.id)
            ?: error("Provider credentials are missing.")

        if (provider.id == HarnessProvider.ID) {
            if (c.providers.wire(model) == null) {
                HarnessProvider.probeWire(model)?.let { c.providers.pinWire(model, it.name) }
            }
            HarnessProvider.pins = c.providers.harnessWires.first()
        }

        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val output = StringBuilder()
        ProviderFactory.create(provider).streamChat(
            provider.copy(model = model),
            apiKey,
            systemPrompt(projectName, now),
            turns.map { ChatMessage(role = it.role, text = it.text) },
            emptyList(),
            RequestOptions(maxOutputTokens = 2_048, thinking = ThinkingLevel.OFF),
        ).collect { event ->
            when (event) {
                is StreamEvent.TextDelta -> output.append(event.text)
                is StreamEvent.Batch -> event.events.forEach { nested ->
                    if (nested is StreamEvent.TextDelta) output.append(nested.text)
                    if (nested is StreamEvent.Failure) error(nested.message)
                }
                is StreamEvent.Failure -> error(event.message)
                else -> Unit
            }
        }
        check(output.isNotBlank()) { "AI returned an empty response." }
        return parse(output.toString(), zone, now)
    }

    private fun systemPrompt(projectName: String, now: ZonedDateTime): String = """
        You configure AndroidHarness automations. The active workspace is "$projectName".
        Current local date/time is $now. Time zone is ${now.zone.id}.

        Decide whether the user has supplied enough information to create a useful automation.
        If an important detail is missing or ambiguous, ask ONE short, natural follow-up question.
        Important details are what the agent should do and when it should run. Do not over-question
        when the intent is already clear. A success-check command is optional and should only be set
        when it is obvious and useful.

        Supported schedules:
        - MANUAL: runs only when the user taps Run now.
        - ONCE: one future local date and time.
        - DAILY: every day at a local time.

        Understand natural time phrases such as "tomorrow at 8", "tonight at 11", and
        "every day at 9 AM" using the current local date/time above. If the user asks for a repeat
        pattern outside the supported schedules, ask them to choose a supported schedule.

        Return ONLY one JSON object. No markdown and no text outside JSON.

        If more information is needed:
        {"type":"question","message":"Your short follow-up question"}

        If ready:
        {
          "type":"draft",
          "message":"A short human summary of what you configured",
          "title":"Short automation name",
          "prompt":"Complete autonomous agent instruction with all useful details",
          "checkCommand":"Optional shell command, otherwise empty string",
          "schedule":"MANUAL|ONCE|DAILY",
          "date":"YYYY-MM-DD for ONCE, otherwise empty string",
          "hour":0,
          "minute":0
        }

        For ONCE, date/hour/minute must resolve to a future instant. For DAILY, hour/minute are the
        recurring local time. For MANUAL use hour 8 and minute 0. Never invent project requirements
        the user did not ask for.
    """.trimIndent()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(
            raw: String,
            zone: ZoneId = ZoneId.systemDefault(),
            now: ZonedDateTime = ZonedDateTime.now(zone),
        ): AutomationAiReply {
            val clean = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val start = clean.indexOf('{')
            val end = clean.lastIndexOf('}')
            require(start >= 0 && end > start) { "AI returned an invalid automation response." }
            val obj = json.parseToJsonElement(clean.substring(start, end + 1)).jsonObject
            return when (obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "question" -> {
                    val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    require(message.isNotBlank()) { "AI returned an empty question." }
                    AutomationAiReply.Question(message)
                }
                "draft" -> {
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    require(title.isNotBlank() && prompt.isNotBlank()) { "AI returned an incomplete automation." }
                    val schedule = runCatching {
                        AutomationSchedule.valueOf(obj["schedule"]?.jsonPrimitive?.contentOrNull.orEmpty().uppercase())
                    }.getOrElse { throw IllegalArgumentException("AI returned an unsupported schedule.") }
                    val hour = obj["hour"]?.jsonPrimitive?.intOrNull ?: 8
                    val minute = obj["minute"]?.jsonPrimitive?.intOrNull ?: 0
                    require(hour in 0..23 && minute in 0..59) { "AI returned an invalid time." }
                    val scheduledAt = if (schedule == AutomationSchedule.ONCE) {
                        val date = obj["date"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val instant = LocalDate.parse(date).atTime(hour, minute).atZone(zone)
                        require(instant.isAfter(now)) { "AI chose a time that has already passed." }
                        instant.toInstant().toEpochMilli()
                    } else null
                    val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    AutomationAiReply.Draft(
                        message = message.ifBlank { "Your automation is ready to review." },
                        automation = AutomationAiDraft(
                            title = title,
                            prompt = prompt,
                            checkCommand = obj["checkCommand"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                            schedule = schedule,
                            scheduledAt = scheduledAt,
                            hour = hour,
                            minute = minute,
                        ),
                    )
                }
                else -> throw IllegalArgumentException("AI returned an invalid automation response.")
            }
        }
    }
}
