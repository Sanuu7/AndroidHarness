package com.androidharness.app.chatgpt

import com.androidharness.app.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ChatGptAccountsTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun account(id: String, models: List<ModelEntry> = listOf(ModelEntry("sol"))) = ChatGptRegistration(id,
        issuer = ChatGptProtocol.ISSUER, subject = id, email = "$id@example.test", accessToken = "fixture-$id",
        expiresAt = System.currentTimeMillis() / 1000 + 3600, scopes = setOf(ChatGptProtocol.PLAN_SCOPE), models = models)
    private fun catalog(vararg ids: String) = "{\"models\":[" + ids.joinToString(",") {
        "{\"slug\":\"$it\",\"display_name\":\"$it\",\"visibility\":\"list\"}"
    } + "]}"

    private class Fixture(val manager: ChatGptAccounts, val stored: () -> String, val requests: MutableList<String>)
    private fun fixture(accounts: List<ChatGptRegistration>, enabled: Boolean = true,
        catalogs: Map<String, String> = emptyMap()): Fixture {
        var stored = json.encodeToString(ChatGptStore("fixture-host", accounts, autoSwitch = enabled))
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(ChatGptProtocol.RESOURCE + "/models", request.url.toString())
            val id = request.header("Authorization")!!.removePrefix("Bearer fixture-")
            requests += id
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (id == "offline") 503 else 200).message("fixture")
                .body((catalogs[id] ?: catalog("sol", "luna")).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Fixture(ChatGptAccounts({ stored }, { stored = it }, {}, http = client, refreshOnStart = false), { stored }, requests)
    }
    private fun config(id: String) = ProviderConfig(ChatGptProtocol.providerId(id), "ChatGPT", ProviderType.OPENAI_RESPONSES,
        ChatGptProtocol.RESOURCE, "sol")

    @Test fun `fresh account catalog keeps same model or uses compatible preference`() = runBlocking {
        val f = fixture(listOf(account("a"), account("b").copy(fallbackModel = "luna")), catalogs = mapOf("b" to catalog("terra", "luna")))
        val next = f.manager.nextAccount(config("a"), setOf(config("a").id), "", emptyList(), emptyList())!!
        assertEquals(config("b").id, next.id); assertEquals("luna", next.model)
        assertEquals(listOf("b"), f.requests)
        val keep = f.manager.selectAccount(config("b").id, "terra")
        assertEquals("terra", keep.model)
    }

    @Test fun `old verified model cannot bypass fresh entitlement catalog`() = runBlocking {
        val f = fixture(listOf(account("a"), account("b").copy(verifiedModels = listOf(ModelEntry("sol")), fallbackModel = "sol")),
            catalogs = mapOf("b" to catalog("luna")))
        assertEquals("luna", f.manager.nextAccount(config("a"), setOf(config("a").id), "", emptyList(), emptyList())!!.model)
    }

    @Test fun `disabled disconnected opted out empty and unavailable accounts are skipped`() = runBlocking {
        val f = fixture(listOf(account("a"), account("signed-out").copy(accessToken = null), account("excluded").copy(useForAutoSwitch = false),
            account("offline"), account("empty"), account("c")), catalogs = mapOf("empty" to catalog()))
        val next = f.manager.nextAccount(config("a"), setOf(config("a").id), "", emptyList(), emptyList())!!
        assertEquals(config("c").id, next.id); assertEquals(listOf("offline", "empty", "c"), f.requests)
        assertTrue(f.manager.stateAccount(config("offline").id).connected)
        f.manager.setAutoSwitch(false)
        assertNull(f.manager.nextAccount(config("a"), setOf(config("a").id), "", emptyList(), emptyList()))
    }

    @Test fun `settings and limit markers survive restart without mixing identities`() = runBlocking {
        val f = fixture(listOf(account("a"), account("b")), enabled = false)
        f.manager.setAutoSwitch(true)
        f.manager.setFallback(config("b").id, false, "sol")
        f.manager.recordUsageLimit(config("a").id)
        val restored = ChatGptAccounts(f.stored, {}, {}, refreshOnStart = false)
        assertTrue(restored.state.value.autoSwitch)
        assertTrue(restored.stateAccount(config("a").id).usageLimited)
        assertFalse(restored.stateAccount(config("b").id).useForAutoSwitch)
        assertEquals("sol", restored.stateAccount(config("b").id).fallbackModel)
        f.manager.selectAccount(config("a").id, "sol")
        assertTrue("A model refresh cannot prove that usage has reset", f.manager.stateAccount(config("a").id).usageLimited)
        f.manager.recordUsageSuccess(config("a").id)
        assertFalse(f.manager.stateAccount(config("a").id).usageLimited)
        val stored = json.decodeFromString<ChatGptStore>(f.stored())
        assertEquals(listOf("fixture-a", "fixture-b"), stored.accounts.map { it.accessToken })
    }

    @Test fun `old credential store migrates with automatic switching off`() {
        val original = """{"hostId":"host","accounts":[],"welcomeSeen":true}"""
        val restored = ChatGptAccounts({ original }, {}, {}, refreshOnStart = false)
        assertFalse(restored.state.value.autoSwitch)
    }
}
