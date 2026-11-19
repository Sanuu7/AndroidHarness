package com.androidharness.app.data.env

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Where Termux packages come from. The package index (with every file's SHA-256) is always read
 * from Termux's own servers, so a mirror can only make a download faster: anything it serves
 * that does not match the official checksum is thrown away and fetched from the next one.
 */
internal object PackageMirrors {
    /** Termux's own hosts. The Cloudflare one is served from edges worldwide. */
    val official = listOf(
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main",
    )

    /** Public mirrors of termux-main that carry the same pool layout. */
    val mirrors = listOf(
        "https://mirror.mwt.me/termux/main",
        "https://grimler.se/termux/termux-main",
        "https://ftp.fau.de/termux/termux-main",
        "https://mirror.accum.se/mirror/termux.dev/termux-main",
        "https://mirror.csclub.uwaterloo.ca/termux/termux-main",
        "https://plug-mirror.rcac.purdue.edu/termux/termux-main",
        "https://mirror.quantum5.ca/termux/termux-main",
        "https://mirrors.middlendian.com/termux/termux-main",
        "https://mirror.rinarin.dev/termux/termux-main",
        "https://mirror.albony.in/termux/termux-main",
        "https://mirror.freedif.org/termux/termux-main",
        "https://linux.domainesia.com/applications/termux/termux-main",
        "https://termux.librehat.com/apt/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
        "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
        "https://mirrors.nju.edu.cn/termux/apt/termux-main",
        "https://mirror.sjtu.edu.cn/termux/termux-main",
        "https://mirrors.aliyun.com/termux/termux-main",
    )

    private const val RANK_TTL_MS = 6 * 60 * 60 * 1000L
    private const val PROBE_MS = 4_000L
    private val lock = Mutex()
    private var ranked: List<String>? = null
    private var rankedAt = 0L

    /**
     * Every host that answered, quickest first. A probe asks each for the small Release file at
     * once, so the pick reflects this network right now. The result is kept for a few hours.
     */
    suspend fun ranked(client: OkHttpClient): List<String> = lock.withLock {
        val now = System.currentTimeMillis()
        ranked?.takeIf { now - rankedAt < RANK_TTL_MS }?.let { return@withLock it }
        val probe = client.newBuilder().connectTimeout(PROBE_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_MS, TimeUnit.MILLISECONDS).callTimeout(PROBE_MS, TimeUnit.MILLISECONDS).build()
        val timings = coroutineScope {
            (official + mirrors).map { base ->
                async(Dispatchers.IO) { base to withTimeoutOrNull(PROBE_MS + 500) { latency(probe, base) } }
            }.awaitAll()
        }
        val reachable = timings.mapNotNull { (base, ms) -> ms?.let { base to it } }.sortedBy { it.second }.map { it.first }
        // Keep the official hosts as the last resort even when the probe could not reach them.
        val order = (reachable + official).distinct()
        if (reachable.isNotEmpty()) { ranked = order; rankedAt = now }
        order
    }

    /** Forget the ranking, for example after the network changed and every pick failed. */
    suspend fun reset() = lock.withLock { ranked = null }

    private suspend fun latency(client: OkHttpClient, base: String): Long? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        runCatching {
            client.newCall(Request.Builder().url("$base/dists/stable/Release").head().build()).execute().use { response ->
                if (response.isSuccessful) (System.nanoTime() - started) / 1_000_000 else null
            }
        }.getOrNull()
    }
}
