package com.androidharness.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Fast, lightweight local TCP port probe for web preview detection.
 */
object LocalPortProbe {

    val COMMON_PORTS = listOf(3000, 5173, 8000, 8080, 4321, 8081, 5000, 8888, 4200, 9000)

    /**
     * Check whether a specific port is actively listening on localhost.
     */
    fun isPortOpen(port: Int, timeoutMs: Int = 60): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Probe a list of ports concurrently and return all active open ports.
     */
    suspend fun probe(ports: List<Int> = COMMON_PORTS, timeoutMs: Int = 60): List<Int> =
        withContext(Dispatchers.IO) {
            ports.map { port ->
                async {
                    if (isPortOpen(port, timeoutMs)) port else null
                }
            }.awaitAll().filterNotNull()
        }

    /**
     * Extracts potential localhost or 127.0.0.1 port numbers from output text (e.g. from dev server logs).
     */
    fun extractPortsFromText(text: String): List<Int> {
        val regex = Regex("""(?:localhost|127\.0\.0\.1|0\.0\.0\.0):(\d{2,5})""", RegexOption.IGNORE_CASE)
        return regex.findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..65535 }
            .distinct()
            .toList()
    }

    /**
     * Checks whether a URL is a localhost web address.
     */
    fun isLocalhostUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://localhost") ||
            lower.startsWith("https://localhost") ||
            lower.startsWith("http://127.0.0.1") ||
            lower.startsWith("https://127.0.0.1") ||
            lower.startsWith("localhost:") ||
            lower.startsWith("127.0.0.1:")
    }

    /**
     * Extracts the port from a localhost URL or bare host:port string.
     * Falls back to the scheme default (80/443) when no explicit port;
     * null when no port can be determined.
     */
    fun portOfUrl(url: String): Int? {
        val trimmed = url.trim()
        try {
            val port = java.net.URI(trimmed).port
            if (port in 1..65535) return port
        } catch (_: Exception) {
        }
        extractPortsFromText(trimmed).firstOrNull()?.let { return it }
        if (!isLocalhostUrl(trimmed)) return null
        return if (trimmed.lowercase().startsWith("https")) 443 else 80
    }

    /**
     * True when the URL's port currently accepts TCP connections on loopback.
     */
    suspend fun isUrlLive(url: String, timeoutMs: Int = 60): Boolean {
        val port = portOfUrl(url) ?: return false
        return withContext(Dispatchers.IO) { isPortOpen(port, timeoutMs) }
    }

    /**
     * Normalizes a localhost URL or port string to standard `http://localhost:<port>/` format.
     */
    fun normalizeLocalUrl(input: String): String {
        val trimmed = input.trim()
        val portOnly = trimmed.toIntOrNull()
        if (portOnly != null && portOnly in 1..65535) {
            return "http://localhost:$portOnly"
        }
        val lower = trimmed.lowercase()
        if (lower.startsWith("localhost:") || lower.startsWith("127.0.0.1:")) {
            return "http://$trimmed"
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "http://$trimmed"
        }
        return trimmed
    }
}
