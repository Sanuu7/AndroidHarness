package com.androidharness.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.ln

/** One logcat filter (`adb logcat -s HarnessResearch`) shows a research turn's timeline. */
internal const val RESEARCH_TIMING_TAG = "HarnessResearch"

/** Logs a timing line; silent on the JVM test runner, where android.util.Log is a stub. */
internal fun researchTiming(message: String) {
    runCatching { android.util.Log.d(RESEARCH_TIMING_TAG, message) }
}

/** The passages one read page contributed, in page order. */
internal data class PagePassages(val result: WebSearchResult, val passages: List<String>)

/**
 * Picks the parts of read pages that answer a query, so the model gets a few
 * thousand relevant characters from several sites instead of whole pages it
 * has to request one round at a time. Ranking is BM25 over paragraph chunks:
 * cheap enough for a phone and good at the "which paragraph mentions these
 * things" job that matters here.
 */
internal object SearchPassages {
    private const val CHUNK_CHARS = 700
    private const val MIN_CHUNK_CHARS = 80
    private val wordPattern = Regex("[\\p{L}\\p{N}]+")
    private val stopwords = setOf(
        "the", "and", "for", "are", "was", "were", "with", "that", "this", "from", "what", "which", "who",
        "how", "why", "when", "where", "does", "did", "can", "could", "should", "would", "will", "about",
        "into", "than", "then", "there", "their", "they", "them", "have", "has", "had", "you", "your",
        "its", "not", "but", "any", "all", "out", "our", "is", "of", "to", "in", "on", "at", "by", "an",
        "a", "or", "as", "be", "it", "do", "vs", "me", "my", "i",
    )

    fun terms(query: String): List<String> =
        wordPattern.findAll(query.lowercase()).map { it.value }
            .filter { it !in stopwords && (it.length > 1 || it.first().isDigit()) }
            .distinct().toList()

    /** Paragraphs merged up to a readable size; very long paragraphs split at sentence ends. */
    fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            val chunk = current.toString().trim()
            if (chunk.length >= MIN_CHUNK_CHARS) out += chunk
            current.clear()
        }
        for (raw in text.split(Regex("\\n+"))) {
            val paragraph = raw.trim()
            if (paragraph.isEmpty()) continue
            if (paragraph.length > CHUNK_CHARS) {
                flush()
                val sentences = paragraph.split(Regex("(?<=[.!?])\\s+"))
                for (sentence in sentences) {
                    if (current.length + sentence.length > CHUNK_CHARS && current.isNotEmpty()) flush()
                    current.append(sentence.take(CHUNK_CHARS * 2)).append(' ')
                }
                flush()
                continue
            }
            if (current.length + paragraph.length > CHUNK_CHARS && current.isNotEmpty()) flush()
            current.append(paragraph).append('\n')
        }
        flush()
        return out
    }

    /**
     * Ranks every page's chunks against [query]. Each page with any match keeps
     * its best chunk, so one long page cannot crowd out other sites; the rest
     * of the room goes to the highest scores, at most [perPage] per page.
     */
    fun rank(
        query: String,
        pages: List<Pair<WebSearchResult, String>>,
        maxPassages: Int = 12,
        perPage: Int = 3,
        maxChars: Int = 9_000,
    ): List<PagePassages> {
        val queryTerms = terms(query)
        if (queryTerms.isEmpty() || pages.isEmpty()) return emptyList()
        data class Chunk(val page: Int, val index: Int, val text: String, val tf: Map<String, Int>, val length: Int)
        val all = pages.flatMapIndexed { pageIndex, (_, text) ->
            chunks(text).mapIndexed { index, chunk ->
                val words = wordPattern.findAll(chunk.lowercase()).map { it.value }.toList()
                Chunk(pageIndex, index, chunk, words.filter { it in queryTerms }.groupingBy { it }.eachCount(), words.size.coerceAtLeast(1))
            }
        }
        if (all.isEmpty()) return emptyList()
        val averageLength = all.sumOf { it.length }.toDouble() / all.size
        val idf = queryTerms.associateWith { term ->
            val containing = all.count { term in it.tf }
            ln(1.0 + (all.size - containing + 0.5) / (containing + 0.5))
        }
        val k1 = 1.2
        val b = 0.75
        fun score(chunk: Chunk): Double = chunk.tf.entries.sumOf { (term, tf) ->
            val norm = tf * (k1 + 1) / (tf + k1 * (1 - b + b * chunk.length / averageLength))
            (idf[term] ?: 0.0) * norm
        } * (0.5 + 0.5 * chunk.tf.size / queryTerms.size) // favour chunks that cover more of the question
        val scored = all.map { it to score(it) }.filter { it.second > 0.0 }.sortedByDescending { it.second }

        val chosen = LinkedHashSet<Chunk>()
        val perPageCount = HashMap<Int, Int>()
        var chars = 0
        fun take(chunk: Chunk): Boolean {
            if (chosen.size >= maxPassages || chars + chunk.text.length > maxChars) return false
            if ((perPageCount[chunk.page] ?: 0) >= perPage || !chosen.add(chunk)) return false
            perPageCount[chunk.page] = (perPageCount[chunk.page] ?: 0) + 1
            chars += chunk.text.length
            return true
        }
        scored.distinctBy { it.first.page }.forEach { take(it.first) }
        scored.forEach { take(it.first) }

        return pages.indices.mapNotNull { pageIndex ->
            val passages = chosen.filter { it.page == pageIndex }.sortedBy { it.index }.map { it.text }
            if (passages.isEmpty()) null else PagePassages(pages[pageIndex].first, passages)
        }
    }

    private const val HEADER = "Passages from pages read for this search (best matches for the query):"
    private val readLine = Regex("^\\[read \\d+]\\s+(.+)$")

    fun render(pages: List<PagePassages>): String = buildString {
        append(HEADER)
        pages.forEachIndexed { index, page ->
            append("\n[read ").append(index + 1).append("] ").append(page.result.title).append('\n')
            append("   ").append(page.result.url)
            page.result.date?.takeIf { it.isNotBlank() }?.let { append("\n   [published ").append(it).append(']') }
            for (passage in page.passages) {
                append("\n   > ").append(passage.replace(Regex("\\s*\\n\\s*"), " "))
            }
        }
    }

    /** URLs of the pages a web_search result actually read, for the source list. */
    fun readSources(text: String): List<WebSearchResult> {
        val start = text.indexOf(HEADER)
        if (start < 0) return emptyList()
        val lines = text.substring(start).lineSequence().toList()
        val out = ArrayList<WebSearchResult>()
        for (i in lines.indices) {
            val title = readLine.matchEntire(lines[i].trim())?.groupValues?.get(1) ?: continue
            val url = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (url.startsWith("https://") || url.startsWith("http://")) out += WebSearchResult(title, url, "")
        }
        return out
    }
}

/**
 * Reads the top results of one search at the same time, under a hard
 * deadline, and returns the passages that answer the query. A page that is
 * slow, blocked, or JavaScript-only is dropped rather than waited on; the
 * model can still open it with web_fetch or browser_read.
 */
class SearchPageReader internal constructor(
    /** Returns a page's readable text, or null when it could not be read. */
    private val readPage: suspend (String) -> String?,
    private val deadlineMs: Long = 5_000,
) {
    internal data class Outcome(val pages: List<PagePassages>, val attempted: Int, val loaded: Int, val unread: List<String>)

    internal suspend fun read(
        query: String,
        results: List<WebSearchResult>,
        wanted: Int,
        budget: SearchSourceBudget?,
    ): Outcome = coroutineScope {
        // One page per site, and a couple of spares so a blocked page does not cost a source.
        val candidates = diversifyResults(results, maxPerHost = 1)
            .filterNot { it.url.substringBefore('?').endsWith(".pdf", ignoreCase = true) }
            .filter { budget == null || budget.canRead(it.url) }
            .take(wanted + 2)
        if (candidates.isEmpty() || wanted <= 0) return@coroutineScope Outcome(emptyList(), 0, 0, emptyList())
        val started = System.nanoTime()
        val arrivals = Channel<Pair<Int, String?>>(candidates.size)
        val jobs = candidates.mapIndexed { index, result ->
            launch(Dispatchers.IO) {
                val pageStart = System.nanoTime()
                val text = try { readPage(result.url) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                researchTiming("search page ${result.url.toHttpUrlOrNull()?.host}: " +
                    "${(System.nanoTime() - pageStart) / 1_000_000}ms ${if (text.isNullOrBlank()) "dropped" else "${text.length} chars"}")
                arrivals.send(index to text)
            }
        }
        // Stop as soon as enough pages are in: the spares only cover for pages
        // that fail, and waiting for the slowest one cost every search seconds.
        val kept = ArrayList<Pair<Int, String>>()
        var answered = 0
        withTimeoutOrNull(deadlineMs) {
            while (kept.size < wanted && answered < candidates.size) {
                val (index, text) = arrivals.receive()
                answered++
                if (text.isNullOrBlank()) continue
                if (budget == null || budget.claimRead(candidates[index].url)) kept += index to text
            }
        }
        jobs.forEach { it.cancel() }
        arrivals.close()
        // Rank in the search engine's order, not in the order pages happened to load.
        val pages = kept.sortedBy { it.first }.map { candidates[it.first] to it.second }
        val ranked = SearchPassages.rank(query, pages)
        researchTiming("search read: ${pages.size}/${candidates.size} pages in ${(System.nanoTime() - started) / 1_000_000}ms, " +
            "${ranked.sumOf { it.passages.size }} passages")
        val readIndexes = kept.map { it.first }.toSet()
        Outcome(ranked, candidates.size, pages.size,
            candidates.filterIndexed { index, _ -> index !in readIndexes }.take(wanted).map { it.url })
    }
}
