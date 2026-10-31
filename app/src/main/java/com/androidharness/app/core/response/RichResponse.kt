package com.androidharness.app.core.response

import kotlinx.serialization.json.*
import java.net.URI

/** A presentation protocol inside assistant text: providers, history, forks and backups need no adapters. */
object RichResponse {
    const val LANGUAGE = "harness-ui"
    /** Raw visual fences: the model writes markup directly, so nothing needs JSON escaping. */
    const val HTML_LANGUAGE = "harness-html"
    const val SVG_LANGUAGE = "harness-svg"
    private val languages = listOf(LANGUAGE, HTML_LANGUAGE, SVG_LANGUAGE)
    const val MAX_JSON_CHARS = 96_000
    const val MAX_VISUAL_CHARS = 64_000
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Part {
        data class Markdown(val text: String) : Part
        data class Content(val node: Node) : Part
        data object Pending : Part
        /** A raw visual still streaming; its title and loading line come from the opening fence. */
        data class PendingVisual(val title: String, val loading: String) : Part
        data object Unavailable : Part
    }

    private fun hasRichFence(text: String, streaming: Boolean) =
        languages.any { text.contains(it) } || (streaming && text.contains("```harness"))

    /** `harness-html title="Orbit" loading="Placing planets"`: quoted attributes, or a bare title after the language. */
    internal fun fenceAttributes(info: String): Map<String, String> {
        val rest = info.substringAfter(' ', "").trim()
        if (rest.isEmpty()) return emptyMap()
        val quoted = Regex("""([a-z_]+)\s*=\s*"([^"]*)"""").findAll(rest).associate { it.groupValues[1] to it.groupValues[2].trim() }
        return quoted.ifEmpty { mapOf("title" to rest) }
    }

    private fun visualNode(language: String, info: String, body: String): Node? {
        if (body.isBlank() || body.length > MAX_VISUAL_CHARS) return null
        val attrs = fenceAttributes(info)
        val title = attrs["title"].orEmpty().take(80).ifBlank { if (language == SVG_LANGUAGE) "Diagram" else "Interactive visual" }
        if (language == SVG_LANGUAGE) {
            val svg = body.trim()
            if (!svg.startsWith("<svg", ignoreCase = true) || !svg.endsWith("</svg>", ignoreCase = true)) return null
            return Node.Preview(title, "<div class=\"hx-svg\">$svg</div>")
        }
        return Node.Preview(title, body.trim())
    }
    data class Action(val label: String, val prompt: String)
    data class FollowUp(val prompt: String, val title: String, val context: String)
    data class Metric(val label: String, val value: String, val detail: String)
    data class Point(val label: String, val value: Double)
    data class Series(val name: String, val points: List<Point>)
    data class Tab(val title: String, val children: List<Node>)
    data class GalleryImage(val url: String, val caption: String, val source: String)
    data class ProfileFact(val label: String, val value: String, val source: String)
    data class ProfileSource(val title: String, val url: String)
    data class NumberInput(val id: String, val label: String, val value: Double, val min: Double, val max: Double, val step: Double)
    data class Calculation(val label: String, val expression: String, val unit: String)
    data class EvidenceItem(val title: String, val text: String, val url: String, val path: String, val line: Int?, val status: String)
    data class Choice(val label: String, val description: String, val prompt: String)
    data class Step(val title: String, val text: String, val done: Boolean)
    sealed interface Node {
        data class Text(val text: String) : Node
        data class Callout(val title: String, val text: String, val tone: String) : Node
        data class Card(val title: String, val text: String, val children: List<Node>, val actions: List<Action>) : Node
        data class Metrics(val items: List<Metric>) : Node
        data class Chart(val title: String, val points: List<Point>, val kind: String, val unit: String, val caption: String, val series: List<Series> = emptyList()) : Node
        data class Choices(val title: String, val options: List<Choice>) : Node
        data class Steps(val title: String, val items: List<Step>, val checkable: Boolean = false) : Node
        data class Details(val title: String, val text: String) : Node
        data class Link(val title: String, val text: String, val url: String) : Node
        data class Preview(val title: String, val html: String) : Node
        data class Layout(val children: List<Node>, val columns: Boolean) : Node
        data class Table(val title: String, val columns: List<String>, val rows: List<List<String>>, val caption: String) : Node
        data class Tabs(val title: String, val tabs: List<Tab>) : Node
        data class Gallery(val title: String, val images: List<GalleryImage>) : Node
        data class Profile(val name: String, val subtitle: String, val description: String, val image: String,
            val imageSource: String, val facts: List<ProfileFact>, val sources: List<ProfileSource>) : Node
        data class Calculator(val title: String, val inputs: List<NumberInput>, val outputs: List<Calculation>, val caption: String) : Node
        data class Evidence(val title: String, val items: List<EvidenceItem>) : Node
        data class Diff(val title: String, val before: String, val after: String, val path: String, val line: Int?, val language: String) : Node
        data class Writing(val title: String, val text: String, val language: String) : Node
        data object Divider : Node
    }

    /** True while the last rich fence of streaming [text] is still open, i.e. a visual is being written. */
    fun isBuildingVisual(text: String): Boolean {
        val start = text.lastIndexOf("```harness")
        if (start < 0) return false
        val lineStart = text.lastIndexOf('\n', start) + 1
        return parse(text.substring(lineStart), streaming = true).lastOrNull().let { it is Part.Pending || it is Part.PendingVisual }
    }

    /** Recognized fences are withheld until complete. A normal code fence is never interpreted as UI. */
    fun parse(text: String, streaming: Boolean = false): List<Part> {
        if (!hasRichFence(text, streaming)) return listOf(Part.Markdown(text))
        val lines = text.splitToSequence('\n').toList()
        val result = mutableListOf<Part>()
        val markdown = StringBuilder()
        fun flush() { if (markdown.isNotBlank()) result += Part.Markdown(markdown.toString().trimEnd()); markdown.clear() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimStart()
            val fence = line.takeWhile { it == '`' }
            if (fence.length < 3) { markdown.append(lines[i++]).append('\n'); continue }
            val info = line.drop(fence.length).trim()
            val language = info.substringBefore(' ')
            var end = i + 1
            while (end < lines.size) {
                val closing = lines[end].trim()
                if (closing.length >= fence.length && closing.all { it == '`' }) break
                end++
            }
            // A fence whose language is still being typed ("harness-h") is withheld rather than flashed as code.
            if (streaming && end == lines.size && language.startsWith("harness") && languages.none { it == language } &&
                languages.any { it.startsWith(language) }) { flush(); result += Part.Pending; break }
            if (language !in languages) {
                while (i < minOf(end + 1, lines.size)) markdown.append(lines[i++]).append('\n')
                continue
            }
            flush()
            if (end == lines.size) {
                result += when {
                    !streaming -> Part.Unavailable
                    language == LANGUAGE -> Part.Pending
                    else -> fenceAttributes(info).let { Part.PendingVisual(it["title"].orEmpty().take(80), it["loading"].orEmpty().take(80)) }
                }
                break
            }
            val body = lines.subList(i + 1, end).joinToString("\n")
            val node = if (language == LANGUAGE) decode(body) else visualNode(language, info, body)
            result += node?.let(Part::Content) ?: Part.Unavailable
            i = end + 1
        }
        flush()
        return result
    }

    fun decode(body: String): Node? = body.takeIf { it.length <= MAX_JSON_CHARS }?.let {
        runCatching { Decoder().node(componentObject(it), 0) }.getOrNull()
    }

    /** Recover redundant closing brackets only after a complete, otherwise valid object. */
    private fun componentObject(body: String): JsonObject {
        val start = body.indexOfFirst { !it.isWhitespace() }
        require(body.getOrNull(start) == '{')
        val closers = ArrayDeque<Char>()
        var quoted = false
        var escaped = false
        for (index in start until body.length) {
            val char = body[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
                continue
            }
            when (char) {
                '"' -> quoted = true
                '{', '[' -> {
                    require(closers.size < 32)
                    closers.addLast(if (char == '{') '}' else ']')
                }
                '}', ']' -> {
                    require(closers.removeLastOrNull() == char)
                    if (closers.isEmpty()) {
                        val trailing = body.substring(index + 1).filterNot(Char::isWhitespace)
                        require(trailing.length <= 12 && trailing.all { it == '}' || it == ']' })
                        return json.parseToJsonElement(body.substring(start, index + 1)).jsonObject
                    }
                }
            }
        }
        error("Incomplete response component")
    }

    private class Decoder {
        private var count = 0
        fun node(o: JsonObject, depth: Int): Node {
            require(depth <= 6 && ++count <= 96)
            fun children(required: Boolean = true) = o.array("children", 12, required).map { node(it.jsonObject, depth + 1) }
            return when (o.str("type", 24, true)) {
                "text" -> Node.Text(o.str("text", 6000, true))
                "callout" -> Node.Callout(o.str("title", 100, true), o.str("text", 6000, true), o.str("tone", 20).also { require(it in listOf("", "info", "success", "warning")) })
                "card" -> Node.Card(o.str("title", 100, true), o.str("text", 6000), if ("children" in o) children(false) else emptyList(),
                    o.array("actions", 2, required = false).map { it.jsonObject.let { a -> Action(a.str("label", 50, true), a.str("prompt", 1000, true)) } })
                "metrics" -> Node.Metrics(o.array("items", 8).map { it.jsonObject.let { m -> Metric(m.str("label", 80, true), m.str("value", 80, true), m.str("detail", 200)) } })
                "chart" -> {
                    fun points(source: JsonObject) = source.array("points", 120).map { it.jsonObject.let { p -> Point(p.str("label", 60, true), p.number("value")) } }
                    val series = o.array("series", 6, false).map { it.jsonObject.let { item -> Series(item.str("name", 80, true), points(item)) } }
                    val primary = if (series.isEmpty()) points(o) else series.first().points
                    require(series.all { seriesItem -> seriesItem.points.map { it.label } == primary.map { it.label } })
                    Node.Chart(o.str("title", 100, true), primary, o.str("kind", 10).ifBlank { "bar" }.also { require(it in listOf("bar", "line")) },
                        o.str("unit", 24), o.str("caption", 500), series)
                }
                "table" -> {
                    val columns = o.array("columns", 8).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.also { value -> require(value.length <= 80) } ?: error("Expected column") }
                    val rows = o.array("rows", 100).map { row -> (row as? JsonArray ?: error("Expected row")).also { require(it.size == columns.size) }.map { cell ->
                        (cell as? JsonPrimitive)?.takeIf { it.isString }?.content?.also { require(it.length <= 1000) } ?: error("Expected cell text")
                    } }
                    Node.Table(o.str("title", 100, true), columns, rows, o.str("caption", 500))
                }
                "tabs" -> Node.Tabs(o.str("title", 100, true), o.array("tabs", 6).map { it.jsonObject.let { tab ->
                    Tab(tab.str("title", 60, true), tab.array("children", 12).map { child -> node(child.jsonObject, depth + 1) })
                } })
                "gallery" -> Node.Gallery(o.str("title", 100, true), o.array("images", 12).map { it.jsonObject.let { image ->
                    GalleryImage(image.str("url", 2048, true).also(::requireWebUrl), image.str("caption", 500), image.str("source", 2048).also { url -> if (url.isNotBlank()) requireWebUrl(url) })
                } })
                "profile" -> {
                    val sources = o.array("sources", 6).map { it.jsonObject.let { source ->
                        ProfileSource(source.str("title", 100, true), source.str("url", 2048, true).also(::requireWebUrl))
                    } }
                    val image = o.str("image", 2048).also { if (it.isNotBlank()) requireWebUrl(it) }
                    val imageSource = o.str("image_source", 2048).also { if (it.isNotBlank()) requireWebUrl(it) }
                    require(image.isBlank() || imageSource.isNotBlank())
                    val facts = o.array("facts", 8, false).map { it.jsonObject.let { fact ->
                        ProfileFact(fact.str("label", 60, true), fact.str("value", 500, true),
                            fact.str("source", 2048, true).also(::requireWebUrl))
                    } }
                    Node.Profile(o.str("name", 120, true), o.str("subtitle", 160), o.str("description", 2000, true),
                        image, imageSource, facts, sources)
                }
                "calculator" -> {
                    val inputs = o.array("inputs", 6).map { it.jsonObject.let { input ->
                        val low = input.number("min"); val high = input.number("max"); val step = input.number("step")
                        require(low < high && step > 0 && step <= high - low && high - low <= 1e15)
                        NumberInput(input.str("id", 30, true).also { id -> require(id.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*"))) },
                            input.str("label", 80, true), input.number("value").also { value -> require(value in low..high) }, low, high, step)
                    } }
                    require(inputs.map { it.id }.distinct().size == inputs.size)
                    val outputs = o.array("outputs", 6).map { it.jsonObject.let { output ->
                        Calculation(output.str("label", 80, true), output.str("expression", 300, true).also { expression ->
                            require(CalculatorMath.isValid(expression, inputs.map { it.id }.toSet()))
                        }, output.str("unit", 24))
                    } }
                    Node.Calculator(o.str("title", 100, true), inputs, outputs, o.str("caption", 500))
                }
                "evidence" -> Node.Evidence(o.str("title", 100, true), o.array("items", 12).map { it.jsonObject.let { item ->
                    EvidenceItem(item.str("title", 100, true), item.str("text", 2000), item.str("url", 2048).also { url -> if (url.isNotBlank()) requireWebUrl(url) },
                        item.str("path", 1024).also(::requireFilePath), item.lineNumber(), item.str("status", 20).also { status -> require(status in listOf("", "passed", "failed", "unverified")) })
                } })
                "diff" -> Node.Diff(o.str("title", 100, true), o.str("before", 24000), o.str("after", 24000), o.str("path", 1024).also(::requireFilePath), o.lineNumber(), o.str("language", 30)).also {
                    require(it.before.lines().size <= 400 && it.after.lines().size <= 400)
                }
                "writing" -> Node.Writing(o.str("title", 100, true), o.str("text", 24000, true), o.str("language", 30))
                "choices" -> Node.Choices(o.str("title", 100, true), o.array("options", 6).map { it.jsonObject.let { c -> Choice(c.str("label", 100, true), c.str("description", 1000), c.str("prompt", 1000)) } })
                "steps" -> Node.Steps(o.str("title", 100), o.array("items", 12).map { it.jsonObject.let { s -> Step(s.str("title", 100, true), s.str("text", 1000), (s["done"] as? JsonPrimitive)?.booleanOrNull ?: false) } },
                    if ("checkable" in o) requireNotNull((o["checkable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull) else false)
                "details" -> Node.Details(o.str("title", 100, true), o.str("text", 6000, true))
                "link" -> Node.Link(o.str("title", 100, true), o.str("text", 500), o.str("url", 2048, true).also(::requireWebUrl))
                "preview" -> Node.Preview(o.str("title", 80, true), o.str("html", 64_000, true))
                "column", "columns" -> Node.Layout(children(), o.str("type", 24) == "columns")
                "divider" -> Node.Divider
                else -> error("Unsupported response component")
            }
        }
    }
    private fun requireWebUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme?.lowercase() in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
    }
    private fun requireFilePath(path: String) { require(path.none { it.isISOControl() } && !path.contains("://")) }
    private fun JsonObject.lineNumber(): Int? = if ("line" !in this) null else
        requireNotNull((this["line"] as? JsonPrimitive)?.intOrNull).also { require(it in 1..10_000_000) }
    private fun JsonObject.number(key: String): Double = requireNotNull((this[key] as? JsonPrimitive)?.doubleOrNull)
        .also { require(it.isFinite() && kotlin.math.abs(it) <= 1e15) }

    private fun JsonObject.str(key: String, max: Int, required: Boolean = false): String {
        val element = this[key]
        if (element == null) { require(!required); return "" }
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected text")
        require(value.length <= max && (!required || value.isNotBlank()))
        return value
    }
    private fun JsonObject.array(key: String, max: Int, required: Boolean = true): JsonArray {
        val value = this[key] ?: return JsonArray(emptyList()).also { require(!required) }
        return (value as? JsonArray ?: error("Expected a list")).also { require(it.size <= max && (!required || it.isNotEmpty())) }
    }

    fun plainText(text: String, streaming: Boolean = false): String = if (!hasRichFence(text, streaming)) text else parse(text, streaming).joinToString("\n\n") {
        when (it) {
            is Part.Markdown -> it.text; is Part.Content -> describe(it.node)
            Part.Pending, is Part.PendingVisual -> "Preparing content…"; Part.Unavailable -> "Rich content unavailable"
        }
    }
    /** Legacy URL discovery must not probe URLs inside isolated preview code or duplicate native link cards. */
    fun markdownText(text: String): String = if (!hasRichFence(text, false)) text else parse(text).filterIsInstance<Part.Markdown>().joinToString("\n\n") { it.text }
    /** Image discovery for follow-up inspection includes native cards, not just Markdown. */
    fun images(text: String): List<GalleryImage> = buildList {
        fun visit(node: Node) {
            when (node) {
                is Node.Profile -> if (node.image.isNotBlank()) add(GalleryImage(node.image, node.name, node.imageSource))
                is Node.Gallery -> addAll(node.images)
                is Node.Card -> node.children.forEach(::visit)
                is Node.Layout -> node.children.forEach(::visit)
                is Node.Tabs -> node.tabs.forEach { it.children.forEach(::visit) }
                else -> Unit
            }
        }
        parse(text).filterIsInstance<Part.Content>().forEach { visit(it.node) }
    }
    fun describe(node: Node): String = when (node) {
        is Node.Text -> node.text
        is Node.Callout -> "${node.title}\n${node.text}"
        is Node.Card -> (listOf(node.title, node.text) + node.children.map(::describe)).filter(String::isNotBlank).joinToString("\n")
        is Node.Metrics -> node.items.joinToString("\n") { "${it.label}: ${it.value}${if (it.detail.isBlank()) "" else " · ${it.detail}"}" }
        is Node.Chart -> node.title + "\n" + (node.series.ifEmpty { listOf(Series("", node.points)) }).joinToString("\n") { series ->
            (if (series.name.isBlank()) "" else series.name + "\n") + series.points.joinToString("\n") { "${it.label}: ${number(it.value)} ${node.unit}" }
        } + "\n" + node.caption
        is Node.Choices -> node.title + "\n" + node.options.joinToString("\n") { "${it.label}: ${it.description}" }
        is Node.Steps -> node.title + "\n" + node.items.mapIndexed { index, step -> "${if (node.checkable) { if (step.done) "[x]" else "[ ]" } else "${index + 1}."} ${step.title} ${step.text}" }.joinToString("\n")
        is Node.Details -> "${node.title}\n${node.text}"
        is Node.Link -> "${node.title}\n${node.text}\n${node.url}"
        is Node.Preview -> "${node.title} (interactive preview)"
        is Node.Layout -> node.children.joinToString("\n", transform = ::describe)
        is Node.Table -> node.title + "\n" + node.columns.joinToString(" | ") + "\n" + node.rows.joinToString("\n") { it.joinToString(" | ") } + "\n" + node.caption
        is Node.Tabs -> node.title + "\n" + node.tabs.joinToString("\n") { it.title + "\n" + it.children.joinToString("\n", transform = ::describe) }
        is Node.Gallery -> node.title + "\n" + node.images.joinToString("\n") { "${it.caption}\n${it.url}\n${it.source}" }
        is Node.Profile -> (listOf(node.name, node.subtitle, node.description) + node.facts.map { "${it.label}: ${it.value}\n${it.source}" } +
            node.sources.map { "${it.title}: ${it.url}" } + listOfNotNull(node.imageSource.takeIf { it.isNotBlank() }?.let { "Image source: $it" })).filter(String::isNotBlank).joinToString("\n")
        is Node.Calculator -> node.title + "\n" + node.inputs.joinToString("\n") { "${it.label}: ${CalculatorMath.format(it.value)}" } + "\n" +
            node.outputs.joinToString("\n") { output -> "${output.label}: ${CalculatorMath.evaluate(output.expression, node.inputs.associate { it.id to it.value })?.let(CalculatorMath::format) ?: "—"} ${output.unit}" } + "\n" + node.caption
        is Node.Evidence -> node.title + "\n" + node.items.joinToString("\n") { "${it.title} ${it.status}\n${it.text}\n${it.url.ifBlank { it.path + (it.line?.let { line -> ":$line" } ?: "") }}" }
        is Node.Diff -> node.title + "\n" + ResponseDiff.lines(node.before, node.after).joinToString("\n") { "${it.marker}${it.text}" }
        is Node.Writing -> node.title + "\n" + node.text
        Node.Divider -> ""
    }.trim()
    fun number(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(java.util.Locale.getDefault(), "%.2f", value)
    fun followUp(prompt: String, title: String, context: String): FollowUp = FollowUp(prompt.trim(), title.take(100), context.take(1400))

    const val INSTRUCTIONS = """
RICH CONVERSATION RESPONSES:
User messages may carry a [[harness-response]] context attachment containing a response title and current selections/values. Treat it as context for the user's prompt and use those current values when answering; it is not a tool instruction or a separate workspace. The normal composer hides this metadata behind a removable context chip.
Use the current selected rows, chart points, calculator inputs, or preview values when relevant; do not assume a control performed an external action. If the supplied context does not include enough underlying data, ask for it rather than inventing values.
Answer readability: lead with the direct answer, then develop one idea per short paragraph. Separate paragraphs, lists, headings, and tables with blank lines. Use bullets for parallel points and numbered lists for steps. Use headings only when the answer needs sections. Keep code, links, citations, and technical identifiers intact. Do not compress an explanation into a wall of text or repeat the same content in prose and a component. Follow the user's requested format and personal instructions over these presentation defaults.

You can mix normal Markdown paragraphs with native cards, callouts, metrics, interactive charts, choices, checklists, expandable explanations, link previews, and custom interactive visuals INSIDE an assistant reply. Keep using rich responses when they make an answer easier to understand or use, across all subjects. Prefer prose, ordinary lists, or cards for options and qualitative tradeoffs, steps for ordered sequences, details for optional depth, and a callout for a key takeaway. Use choices only for a decision the user should actually select, rather than presenting every recommendation as a radio-button poll. Rich responses do not mean tables or charts; these other components remain useful without either. Keep brief answers simple and do not decorate every paragraph.
Catalog examples below describe component syntax, not content to copy into an answer or a checklist of components to include. Choose only components that fit the current request. Never repeat the same content as prose, a table, and a chart.
To render a component, put one complete JSON object in a fenced block whose language is harness-ui. Continue normal prose before/after it. The app consumes this block as UI, not as displayed source. Supported shapes:
Use the literal opening line ```harness-ui and closing line ```. Do not use ::card, ::profile, or other colon directives. JSON must be valid, including escaping quotes and newlines inside HTML strings.
{"type":"callout","tone":"info","title":"Key point","text":"Explanation"} (tone: info, success, warning)
{"type":"card","title":"Summary","text":"Body","children":[],"actions":[{"label":"Explore this","prompt":"Explain this option in more detail"}]}
{"type":"metrics","items":[{"label":"Total","value":"42","detail":"Based on the supplied data"}]}
{"type":"chart","title":"Measured latency","kind":"bar","unit":"ms","points":[{"label":"Run A","value":12},{"label":"Run B","value":18}],"caption":"Example structure; replace with supplied or verified measurements"} (kind: bar or line; tap points and view data)
{"type":"choices","title":"Choose an approach","options":[{"label":"Option A","description":"Tradeoffs","prompt":"Help me use option A"},{"label":"Option B","description":"Tradeoffs"}]}
{"type":"steps","title":"How it works","items":[{"title":"First step","text":"What happens here"}]} (plain numbered list by default; no checkboxes or completion controls)
The steps heading is optional; omit it or use an empty title when an enclosing card already supplies the heading. Each item still needs a nonempty title. Keep at most 12 items per steps component.
{"type":"steps","title":"Your packing checklist","checkable":true,"items":[{"title":"Pack your charger","text":"Check this after packing it","done":false}]} (opt-in checklist ONLY when the user asks for one or completion tracking is useful for concrete tasks they are carrying out)
Presentation is not an interaction request: feature lists, pros and cons, comparisons, key layout upgrades, recommendations, roadmaps, brainstorming, and explanations should use ordinary bullets, prose, cards, or plain numbered steps. Do not add checkboxes, checked-off claims, checkbox table columns, or a Continue button just because an answer contains several points or suggested actions. For steps, omit checkable or set it to false unless this is an actual task checklist. Do not mark suggestions or existing product features done. This also applies to Markdown task lists and custom previews. Tables show row-selection checkboxes only when the user explicitly turns on row selection in the table menu; do not imitate that with extra checkbox columns.
{"type":"details","title":"Why this works","text":"The longer explanation"}
{"type":"link","title":"Resource","text":"Why it is useful","url":"https://example.com"}
{"type":"column","children":[...]} and {"type":"columns","children":[...]} compose cards and text; columns stack on phones. {"type":"text","text":"..."} and {"type":"divider"} can be children. Keep nesting shallow. At most 2 actions per card. Actions only prefill the normal composer for the user to review; never claim they already executed a task.
Additional native components work in BOTH Chat and Code. Prefer these over custom HTML when they fit. Put each object in its own complete harness-ui fence, never bare JSON. All local controls operate inside the reply; they do not run tools, apply file changes, send a message, or alter project settings. User selections/values can attach removable context to the normal composer. Honor that context on the next user message.
{"type":"table","title":"Recorded measurements","columns":["Run","Latency, ms"],"rows":[["A","12"],["B","18"]],"caption":"Example structure; replace with supplied or verified measurements"} (filter, numeric/text column sorting, row selection, pagination, CSV copy/share; up to 8 columns and 100 rows; cells must be strings)
Both Markdown and native tables can be expanded for filtering, sorting, and selecting rows. Use a native table when a title, caption, or direct row discussion helps; keep cell text concise and put source citations and longer explanations in surrounding prose. Do not imply that sorting or filtering edits the original data.
A table column heading may be empty (for example, the row-label column of a comparison). Every row must still have exactly one string cell per column. Close each JSON object exactly once; do not add extra brackets after it.
{"type":"tabs","title":"Explore the answer","tabs":[{"title":"Overview","children":[{"type":"text","text":"Main answer"}]},{"title":"Examples","children":[{"type":"text","text":"A concrete example"}]}]} (up to 6 tabs; nest any supported components in children; selected-tab controls retain state)
{"type":"gallery","title":"Visual comparison","images":[{"url":"https://example.com/actual-image.jpg","caption":"What this image shows","source":"https://example.com/source-page"}]} (swipe, previous/next, expand with zoom, and source links; up to 12 images; ONLY actual image URLs returned by tools or supplied by the user, never invented URLs; respect the chat image setting)
{"type":"profile","name":"Subject name","subtitle":"Verified role or category","description":"A short introduction supported by the sources below.","image":"https://example.com/actual-portrait.jpg","image_source":"https://example.com/biography","facts":[{"label":"Known for","value":"A verified contribution","source":"https://example.com/biography"}],"sources":[{"title":"Biography","url":"https://example.com/biography"}]} (native knowledge/profile card for a person, place, organization, or other named subject; optional image/image_source, up to 8 sourced facts and 6 source links; sources required; use only pages actually read and image URLs returned by tools or supplied by the user; omit unverified facts and resolve ambiguous names before making a card; respect the chat image setting)
{"type":"chart","title":"Performance over time","kind":"line","unit":"ms","series":[{"name":"Before","points":[{"label":"Day 1","value":18},{"label":"Day 2","value":20}]},{"name":"After","points":[{"label":"Day 1","value":12},{"label":"Day 2","value":14}]}],"caption":"Measured data or clearly marked illustrative values"} (bar or line, up to 6 datasets and 120 points each, aligned labels in exactly the same order; legends toggle datasets, visible-range sliders zoom the data, data view exposes exact values; existing single-series points form still works)
{"type":"calculator","title":"Try different values","inputs":[{"id":"hours","label":"Hours","value":4,"min":0,"max":24,"step":0.5},{"id":"rate","label":"Rate","value":10,"min":0,"max":100,"step":1}],"outputs":[{"label":"Total","expression":"hours * rate","unit":"credits"}],"caption":"Illustrative estimate; explain assumptions"} (up to 6 numeric inputs and 6 calculated outputs, sliders and editable numbers; use input IDs in arithmetic expressions; supports + - * / ^, parentheses, min(a,b), max(a,b), abs(x), sqrt(x), round(x); NO scripts, functions beyond this list, network, or tool actions)
{"type":"evidence","title":"Sources and checks","items":[{"title":"Supporting reference","text":"The claim this supports","url":"https://example.com/reference"},{"title":"Focused test","text":"Actual command and observed result","path":"src/example.kt","line":12,"status":"passed"}]} (up to 12 items; optional url, workspace path, positive line, status passed/failed/unverified; file navigation is available only in Code; statuses are agent-reported, never claim a test passed without an actual successful tool result; prefer unverified when uncertain; preserve claim-level citations in prose)
{"type":"diff","title":"Compare the change","before":"Original text or code","after":"Revised text or code","path":"src/example.kt","line":12,"language":"kotlin"} (line additions/removals, Before/After views, expand, copy/share; path/line/language optional; up to 400 lines per side; comparisons never apply edits; show real observed file changes for completed work, and label proposed changes as proposals)
{"type":"writing","title":"Ready to copy","text":"The complete reusable text, command, configuration, or code","language":""} (exact copy and user-initiated share, expandable body; optional language for code/config; keep explanations outside this block; up to 24000 characters)
Use tabs for genuinely separate views, galleries for visual comparisons, calculators for what-if questions, evidence for cited findings or actual checks, diffs for before/after text or code, and writing blocks for reusable deliverables. Keep short answers simple and avoid repeating the same content as both prose and components. These are available in ordinary answers without asking permission or enabling a special mode.
CUSTOM VISUALS (diagrams, custom charts, explorable widgets, simulations, mockups, illustrations) beyond the native components: write the markup DIRECTLY in a raw fence, with no JSON and no escaping.
```harness-svg title="2-5 word title"
<svg viewBox="0 0 640 360" role="img" aria-label="The takeaway">…</svg>
```
```harness-html title="2-5 word title" loading="Short status while it streams"
<div class="hx-stack">…</div><script>…</script>
```
Use harness-svg for a static diagram (exactly one <svg>), harness-html when you need script, controls or several elements. Before your first custom visual of a kind in a conversation, call visual_guide with the matching modules (diagram, chart, interactive, mockup, art) when that tool is available; it returns the detailed design rules. Every visual gets the user's app theme as CSS variables (--background --foreground --muted --surface --surface-2 --border --accent --accent-fg --accent-soft --success --warning --danger, series colors --c1…--c6) and a ready-made class kit (hx-stack, hx-row, hx-grid, hx-card, hx-title, hx-muted, hx-label, hx-stat, hx-chip data-tone, hx-btn, hx-segment, hx-range, hx-bar, hx-table, and SVG classes hx-node, hx-edge, hx-text). Use the tokens and classes instead of hard-coded colors so visuals match the app in light and dark mode. Script helpers: harness.palette(), harness.color(name), and harness.state('Concise current values') to pass the current selection to the user's next message (context only; it cannot run tools or send messages). Keep it self-contained, offline (no CDN, remote fonts, images, fetch, native bridge or workspace changes), responsive at 360px, accessible, under 24 KB when possible (64 KB maximum), and make every control work. Prefer a native component when one fits (for example the chart component for simple bar or line data). Never show a visual's source unless the user asks. Follow-up questions stay in the same conversation and can produce an updated visual in a new reply. The older {"type":"preview","title":"…","html":"…"} harness-ui component still renders, but prefer the raw fences.
Where create_artifact/read_artifact tools are available, use them for a separately managed, versioned/exportable concept when the user asks for an artifact or that workflow is useful. Keep existing artifacts available. Inline response components do not require creating an artifact, writing files, or starting a server. Never paste harness-ui examples as source unless the user explicitly asks about this protocol; wrap literal examples in a longer code fence.

PRESENTATION CHOICE FOR THE CURRENT ANSWER:
Choose the simplest presentation that helps the current answer. Use choices for an actual interactive decision, plain steps for sequences, an explicit checkable checklist only for completion tracking, details for optional depth, and callouts sparingly. Plans and recommendations usually need ordinary lists rather than interactive controls. Comparisons can use concise prose, cards, or a table when aligned attributes make differences easier to scan.
Table selection: use a native or Markdown table when the user requests one or when aligned rows and columns genuinely help compare or look up information. Tables can compare qualitative attributes as well as measurements, but should not replace an explanation or squeeze long paragraphs into cells. Keep headers and cell text concise, make units and uncertainty clear, and place supporting sources and interpretation in nearby prose. Prefer native tables when filtering or selection would help; ordinary Markdown tables can also be expanded for interaction.
Chart selection: use charts only when quantitative data has a meaningful trend, distribution, or relationship that a visual helps explain, or the user explicitly requests a chart. Use supplied, verified, or transparently computed values; never invent scores, rankings, percentages, or measurements to turn a qualitative answer into a chart. Label illustrative values and assumptions clearly. A comparison alone is not a reason for a chart.
Apply these rules to nested components and Markdown too. Reassess each turn; an earlier table or chart is not a reason to generate another. Do not repeat the same information as prose, a table, and a chart. Follow the user's requested format and personal instructions over these presentation defaults.
"""
}
