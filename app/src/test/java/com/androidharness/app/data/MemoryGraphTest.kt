package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGraphTest {
    private fun memory(id: String, text: String, folder: String? = null, category: String = "FACT", keywords: String = "", consolidatedInto: String? = null) =
        MemoryEntity(id, text, category = category, folder = folder, keywords = keywords, createdAt = 0, updatedAt = 0, consolidatedInto = consolidatedInto)

    @Test
    fun `one hub per used folder in folder order`() {
        val graph = MemoryGraph.build(listOf(
            memory("1", "Kotlin build cache lives on the phone", folder = "Tooling"),
            memory("2", "Likes dark themes", category = "PREFERENCE"),
            memory("3", "Old note", consolidatedInto = "x"),
        ), folderOrder = listOf("Empty", "Tooling"))
        assertEquals(listOf("c:PREFERENCE", "f:Tooling"), graph.hubs.map { it.key })
        assertEquals(2, graph.nodes.size)
        assertEquals(1, graph.nodes.first { it.memory.id == "1" }.hub)
    }

    @Test
    fun `shared trigger words and topics link memories, unrelated ones stay apart`() {
        val graph = MemoryGraph.build(listOf(
            memory("a", "Gradle release builds need the signing config", keywords = "gradle"),
            memory("b", "Never uninstall the app, Gradle installs over it", keywords = "gradle"),
            memory("c", "Favourite colour is green"),
            memory("d", "Release signing config is stored in keystore properties"),
        ), emptyList())
        val pairs = graph.links.map { setOf(graph.nodes[it.a].memory.id, graph.nodes[it.b].memory.id) to it.why }
        assertTrue(pairs.any { it.first == setOf("a", "b") && it.second == MemoryGraph.Why.KEYWORD })
        assertTrue(pairs.any { it.first == setOf("a", "d") && it.second == MemoryGraph.Why.TOPIC })
        assertTrue(pairs.none { "c" in it.first })
    }

    @Test
    fun `each memory starts at most a few links`() {
        val memories = (0 until 30).map { memory("$it", "shared topic words everywhere number $it", keywords = "common") }
        val graph = MemoryGraph.build(memories, emptyList())
        val perNode = IntArray(memories.size)
        graph.links.forEach { perNode[it.a]++; perNode[it.b]++ }
        assertTrue(graph.links.size <= memories.size * MemoryGraph.MAX_LINKS_PER_NODE)
    }
}
