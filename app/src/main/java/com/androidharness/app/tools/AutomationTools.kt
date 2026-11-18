package com.androidharness.app.tools

import com.androidharness.app.automation.AutomationManager
import com.androidharness.app.automation.AutomationSchedule
import com.androidharness.app.automation.AutomationTask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where a scheduled message from a Code chat goes: the same chat, project and model. */
data class AutomationTarget(
    val projectId: String,
    val projectName: String,
    val providerId: String?,
    val model: String?,
)

/** Wiring the automation tools need from the app, resolved lazily on each call. */
class AutomationToolHost(
    val target: suspend (sessionId: String) -> AutomationTarget?,
    val manager: () -> AutomationManager,
)

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private val whenFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

private fun describeTime(millis: Long?): String =
    millis?.let { whenFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "not scheduled"

internal fun describeSchedule(task: AutomationTask): String = when (task.schedule) {
    AutomationSchedule.ONCE -> "Once on ${describeTime(task.scheduledAt ?: task.nextRunAt)}"
    AutomationSchedule.HOURLY -> "Every hour"
    AutomationSchedule.DAILY -> "Daily at %02d:%02d".format(task.hour, task.minute)
    AutomationSchedule.MANUAL -> "Manual"
}

/**
 * Lets the Code agent schedule a message into the current chat. Every call is
 * shown to the user for approval in all permission modes, because the message
 * later runs on its own with nobody watching.
 */
class AutomationCreateTool(private val host: AutomationToolHost) : Tool {
    override val name = NAME
    override val description = "Schedule a prompt to be sent to this Code chat later, once at a set time, every hour, " +
        "or daily at a set time. When it fires, the agent runs the prompt in this chat with this chat's model and workspace. " +
        "Use it when the user asks to do something later or on a repeating schedule. The user always approves the schedule " +
        "before it is saved. Times are the device's local time."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "prompt" to Schema.string("The full instruction to send when the schedule fires. Write it so it stands on its own."),
            "title" to Schema.string("Short name shown in the Automations list. Defaults to the first line of the prompt."),
            "schedule" to Schema.string("One of: once, hourly, daily."),
            "run_at" to Schema.string("For once: local date and time as YYYY-MM-DDTHH:MM, for example 2026-11-19T09:00."),
            "in_minutes" to Schema.integer("For once: run this many minutes from now, instead of run_at."),
            "time" to Schema.string("For daily: local time as HH:MM in 24-hour form, for example 08:30."),
            "unmetered_only" to Schema.boolean("Only run on Wi-Fi or another unmetered network. Default false."),
        ),
        required = listOf("prompt", "schedule"),
    )

    /** What the user sees on the approval card, or a reason the request is malformed. */
    fun approvalPreview(args: JsonObject): String {
        val draft = runCatching { draft(args, now = System.currentTimeMillis()) }
        return draft.fold(
            onSuccess = { "${describeSchedule(it)}\n\n${it.prompt.take(600)}" },
            onFailure = { "Invalid schedule: ${it.message}\n\n${args.str("prompt").orEmpty().take(600)}" },
        )
    }

    private fun draft(args: JsonObject, now: Long, target: AutomationTarget? = null, sessionId: String? = null): AutomationTask {
        val prompt = args.str("prompt") ?: throw ToolFailure("prompt is required")
        val title = (args.str("title") ?: prompt.lineSequence().first()).take(64)
        val zone = ZoneId.systemDefault()
        var schedule: AutomationSchedule
        var at: Long? = null
        var hour = 8
        var minute = 0
        when (args.str("schedule")?.lowercase()) {
            "once" -> {
                schedule = AutomationSchedule.ONCE
                val minutes = args["in_minutes"]?.jsonPrimitive?.intOrNull
                at = when {
                    minutes != null -> {
                        if (minutes < 1) throw ToolFailure("in_minutes must be at least 1")
                        now + minutes * 60_000L
                    }
                    args.str("run_at") != null -> try {
                        LocalDateTime.parse(args.str("run_at")).atZone(zone).toInstant().toEpochMilli()
                    } catch (_: Exception) { throw ToolFailure("run_at must look like 2026-11-19T09:00") }
                    else -> throw ToolFailure("once needs run_at or in_minutes")
                }
                if (at <= now) throw ToolFailure("run_at is in the past. It is now ${describeTime(now)}.")
                val local = Instant.ofEpochMilli(at).atZone(zone)
                hour = local.hour; minute = local.minute
            }
            "hourly" -> schedule = AutomationSchedule.HOURLY
            "daily" -> {
                schedule = AutomationSchedule.DAILY
                val time = try { LocalTime.parse(args.str("time") ?: throw ToolFailure("daily needs time")) }
                    catch (e: ToolFailure) { throw e }
                    catch (_: Exception) { throw ToolFailure("time must look like 08:30") }
                hour = time.hour; minute = time.minute
            }
            else -> throw ToolFailure("schedule must be once, hourly or daily")
        }
        return AutomationTask(
            title = title,
            prompt = prompt,
            projectId = target?.projectId.orEmpty(),
            projectName = target?.projectName.orEmpty(),
            providerId = target?.providerId,
            model = target?.model,
            schedule = schedule,
            scheduledAt = at,
            hour = hour,
            minute = minute,
            targetSessionId = sessionId,
            unmeteredOnly = args["unmetered_only"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sessionId = ctx.sessionId ?: return ToolResult(false, "Schedules can only be created from a saved Code chat.")
        val target = host.target(sessionId)
            ?: return ToolResult(false, "This chat has no workspace, so it cannot be scheduled. Open it in a project first.")
        if (target.providerId.isNullOrBlank() || target.model.isNullOrBlank())
            return ToolResult(false, "This chat has no model selected, so the schedule would have nothing to run with.")
        val task = try { draft(args, System.currentTimeMillis(), target, sessionId) }
            catch (e: ToolFailure) { return ToolResult(false, e.message ?: "Invalid schedule") }
        return try {
            host.manager().save(task)
            val saved = host.manager().repository.task(task.id) ?: task
            ToolResult(true, "Scheduled \"${saved.title}\" (id ${saved.id}). ${describeSchedule(saved)}. " +
                "Next run: ${describeTime(saved.nextRunAt)}. The user can see, pause or delete it from the Automations screen.")
        } catch (e: IllegalArgumentException) {
            ToolResult(false, e.message ?: "Could not save the schedule")
        }
    }

    companion object { const val NAME = "automation_create" }
}

/** Lists scheduled messages, this chat's first. */
class AutomationListTool(private val host: AutomationToolHost) : Tool {
    override val name = "automation_list"
    override val description = "List scheduled automations: id, title, schedule, next run, last status and whether each " +
        "targets this chat. Use before changing or deleting one."
    override val isReadOnly = true
    override val parametersSchema = Schema.obj(emptyMap())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val tasks = host.manager().repository.tasks.value
        if (tasks.isEmpty()) return ToolResult(true, "No automations are scheduled. It is now ${describeTime(System.currentTimeMillis())}.")
        val sorted = tasks.sortedByDescending { it.targetSessionId == ctx.sessionId && ctx.sessionId != null }
        val lines = sorted.joinToString("\n") { task ->
            val here = if (ctx.sessionId != null && task.targetSessionId == ctx.sessionId) " [this chat]" else ""
            "- ${task.id}$here: \"${task.title}\" · ${describeSchedule(task)} · " +
                (if (task.enabled) "next ${describeTime(task.nextRunAt)}" else "paused") + " · last ${task.lastStatus.name.lowercase()}"
        }
        return ToolResult(true, "It is now ${describeTime(System.currentTimeMillis())}.\n$lines")
    }
}

/** Pauses, resumes or deletes one automation. */
class AutomationManageTool(private val host: AutomationToolHost) : Tool {
    override val name = "automation_manage"
    override val description = "Pause, resume or delete a scheduled automation by id (from automation_list)."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "id" to Schema.string("The automation id."),
            "action" to Schema.string("One of: pause, resume, delete."),
        ),
        required = listOf("id", "action"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.str("id") ?: return ToolResult(false, "id is required")
        val manager = host.manager()
        val task = manager.repository.task(id) ?: return ToolResult(false, "No automation has id $id. Call automation_list.")
        return try {
            when (args.str("action")?.lowercase()) {
                "pause" -> { manager.pause(id); ToolResult(true, "Paused \"${task.title}\".") }
                "resume" -> { manager.resumeSchedule(id); ToolResult(true, "Resumed \"${task.title}\".") }
                "delete" -> { manager.delete(id); ToolResult(true, "Deleted \"${task.title}\".") }
                else -> ToolResult(false, "action must be pause, resume or delete")
            }
        } catch (e: IllegalArgumentException) {
            ToolResult(false, e.message ?: "Could not change the automation")
        } catch (e: IllegalStateException) {
            ToolResult(false, e.message ?: "Could not change the automation")
        }
    }
}
