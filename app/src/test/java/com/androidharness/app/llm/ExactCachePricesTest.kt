package com.androidharness.app.llm

import org.junit.Assert.*
import org.junit.Test

class ExactCachePricesTest {
    @Test fun `different products on the same host never share prices`() {
        val parsed = ModelsDev.parse("""{
          "one":{"api":"https://relay.example/one/v1","models":{"m":{"cost":{"input":2,"output":5,"cache_read":0.5}}}},
          "two":{"api":"https://relay.example/two/v1","models":{"m":{"cost":{"input":4,"output":5,"cache_read":1}}}}
        }""")
        try {
            ModelsDev.replaceForTesting(parsed.entries, parsed.providers)
            assertEquals(0.5, ModelsDev.exactCachePrices("https://relay.example/one/v1", "m")!!.read!!, 0.0)
            assertEquals(1.0, ModelsDev.exactCachePrices("https://relay.example/two/v1", "m")!!.read!!, 0.0)
            assertNull(ModelsDev.exactCachePrices("https://relay.example/three/v1", "m"))
        } finally { ModelsDev.replaceForTesting(emptyMap()) }
    }

    @Test fun `exact provider prices preserve free rates and never borrow or invent cache rates`() {
        val parsed = ModelsDev.parse("""{
          "free":{"name":"Free","api":"https://free.example/v1","models":{
            "model":{"cost":{"input":0,"output":0,"cache_read":0,"cache_write":0}},
            "unknown-cache":{"cost":{"input":2,"output":5}}}},
          "paid":{"name":"Paid","api":"https://paid.example/v1","models":{
            "model":{"cost":{"input":2,"output":5,"cache_read":0.5}}}}
        }""")
        try {
            ModelsDev.replaceForTesting(parsed.entries, parsed.providers)
            assertEquals(ModelsDev.CachePrices(0.0, 0.0, 0.0), ModelsDev.exactCachePrices("https://free.example/v1", "model"))
            assertEquals(ModelsDev.CachePrices(2.0, 0.5, null), ModelsDev.exactCachePrices("https://paid.example/v1", "model"))
            assertEquals(ModelsDev.CachePrices(2.0, null, null), ModelsDev.exactCachePrices("https://free.example/v1", "unknown-cache"))
            assertNull(ModelsDev.exactCachePrices("https://other.example/v1", "model"))
            assertNull(ModelsDev.exactCachePrices("https://paid.example.evil/v1", "model"))
            assertNull(ModelsDev.exactCachePrices("https://paid.example/v1", "model-new"))
        } finally { ModelsDev.replaceForTesting(emptyMap()) }
    }
}
