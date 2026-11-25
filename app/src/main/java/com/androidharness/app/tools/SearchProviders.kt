package com.androidharness.app.tools

/**
 * Every web_search provider the app knows. Settings, backups, the agent's
 * settings tool and the backend picker all read this list, so adding a
 * provider here is the one place its id has to be registered.
 */
object SearchProviders {
    enum class Kind { FREE, ENGINE, AI }

    class Info(
        val id: String,
        val name: String,
        val kind: Kind,
        /** Where the user signs up for a key. */
        val keyUrl: String,
        /** What the agent should pick this provider for. Shown in the web_search tool description. */
        val strength: String,
        /** Used when the user has saved no key of their own; null means a key is required. */
        val sharedKey: String? = null,
    )

    const val KEYLESS = "keyless"

    /** What the agent is told the public engines are for. */
    const val KEYLESS_STRENGTH = "scrapes DuckDuckGo, Bing, Brave and Google result pages; free but slower and sometimes blocked"

    /**
     * In the order "auto" tries them: general quality and reliability first, the busy shared
     * Marginalia key last. Settings groups them by [Kind], so this order only matters to search.
     */
    val apis: List<Info> = listOf(
        Info("brave", "Brave Search", Kind.ENGINE, "https://brave.com/search/api/",
            "independent web index; solid general results, news and recency filters"),
        Info("tavily", "Tavily", Kind.AI, "https://app.tavily.com/home",
            "agent-oriented results with clean page text; good for research and reading sources"),
        Info("serper", "Serper", Kind.ENGINE, "https://serper.dev/api-keys",
            "Google results; best for exact names, products, local and very popular queries"),
        Info("linkup", "Linkup", Kind.AI, "https://app.linkup.so/api-keys",
            "agent search with page extracts; good for factual questions that need context"),
        Info("exa", "Exa", Kind.AI, "https://dashboard.exa.ai/api-keys",
            "searches by meaning, not keywords; best for descriptive queries, similar sites, papers, companies and people"),
        Info("jina", "Jina", Kind.AI, "https://jina.ai/api-dashboard/key-manager",
            "returns full page text with results when pages will be read; good for deep reading"),
        Info("langsearch", "LangSearch", Kind.AI, "https://langsearch.com/api-keys",
            "free general web search; good as an extra independent source"),
        Info("serpapi", "SerpApi", Kind.ENGINE, "https://serpapi.com/manage-api-key",
            "Google results with dates; small monthly quota, so save it for queries that need Google"),
        Info("firecrawl", "Firecrawl", Kind.AI, "https://www.firecrawl.dev/app/api-keys",
            "web search from a scraping platform; limited credits, use when others come up short"),
        Info("marginalia", "Marginalia", Kind.FREE, "https://about.marginalia-search.com/article/api/",
            "independent index of small, non-commercial and personal sites; blogs, essays and niche topics",
            sharedKey = "public"),
    )

    /** Provider ids that hold saved keys. */
    val apiIds: List<String> = apis.map { it.id }

    /** Every value the provider setting accepts. */
    val ids: List<String> = listOf(KEYLESS) + apiIds

    fun info(id: String?): Info? {
        val clean = id?.trim()?.lowercase() ?: return null
        return apis.firstOrNull { it.id == clean }
    }

    fun name(id: String?): String? = info(id)?.name
}

/**
 * Everything web_search may use on one call: the API providers that are on and
 * ready, in the order "auto" tries them, and whether the public engines are on.
 */
data class SearchSetup(val apis: List<SearchApiConfig> = emptyList(), val keyless: Boolean = true) {
    fun config(provider: String): SearchApiConfig? = apis.firstOrNull { it.provider == provider }

    /** Ids the agent may name, in try order. */
    val providerIds: List<String> get() = apis.map { it.provider } + if (keyless) listOf(SearchProviders.KEYLESS) else emptyList()

    companion object {
        /** A single provider, as the app worked before several could be on: an API, or the public engines when null. */
        fun single(config: SearchApiConfig?): SearchSetup =
            if (config == null) SearchSetup() else SearchSetup(listOf(config), keyless = false)
    }
}
