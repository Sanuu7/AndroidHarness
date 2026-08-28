package com.androidharness.app.tools

import com.androidharness.app.data.env.GitHubProvision
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import com.androidharness.app.data.env.ShizukuManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end GitHub diagnostics (doctor --github). env_status tells the agent
 * what EXISTS; this tells the agent what actually WORKS: one API ping with the
 * configured token (login, account type, plan, real token scopes), the token
 * file's real permissions, git's spawn shell (GIT_SHELL_PATH) and insteadOf
 * transport, and whether branch protection is silently unavailable, the
 * Free-plan paywall returns 403 for rulesets/branch-protection on private
 * repos, which reads as "no protection configured" while force-pushes succeed.
 */
class DoctorTool(
    private val linuxEnv: LinuxEnvironmentManager,
    private val shizuku: ShizukuManager,
    private val router: ShellTierRouter,
    httpClient: OkHttpClient,
) : Tool {
    override val name = "doctor"
    override val description =
        "Run GitHub self-diagnostics: verify the configured token against the API (login, account " +
            "type, plan, granted scopes), check the materialized token file's real permissions, verify " +
            "git's helper shell (GIT_SHELL_PATH) and token URL rewrite, and probe whether branch " +
            "protection is available or paywalled for the account's private repos (Free-plan trap). " +
            "Use this instead of hand-rolling API calls when GitHub operations misbehave."
    override val parametersSchema = Schema.obj(
        mapOf(
            "github" to Schema.boolean(
                "Run the GitHub checks (default true; the only check set in this build).",
            ),
        ),
    )
    override val isReadOnly = true

    private val client = httpClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val github = args["github"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        if (!github) {
            return ToolResult(true, "Nothing to check: pass {\"github\": true} (the default).")
        }
        val cwd = ctx.workspace.shellRoot ?: linuxEnv.shellFallbackRoot
        val lines = mutableListOf<String>()

        val token = linuxEnv.githubToken()
        if (token == null) {
            lines += "[fail] token: none configured; push/PR/private repos need a personal access " +
                "token (Settings → GitHub). Public HTTPS clones still work anonymously."
        } else {
            checkApiUser(lines, token)
            checkTokenFile(lines, ctx)
        }
        checkGitTransport(lines, cwd)
        if (token != null) checkFreePlanTrap(lines, token)

        val fails = lines.count { it.startsWith("[fail]") }
        val warns = lines.count { it.startsWith("[warn]") }
        val summary = when {
            fails > 0 -> "$fails check(s) failed, $warns warning(s)"
            warns > 0 -> "all reachable checks ran: $warns warning(s)"
            else -> "all checks passed"
        }
        return ToolResult(fails == 0, "GitHub doctor: $summary\n" + lines.joinToString("\n"))
    }

    // --- API checks ---------------------------------------------------------

    private class ApiResp(val code: Int, val body: String, private val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private fun api(path: String, token: String): ApiResp {
        val req = Request.Builder()
            .url("https://api.github.com$path")
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .build()
        return client.newCall(req).execute().use { resp ->
            ApiResp(
                resp.code,
                resp.body?.string().orEmpty(),
                resp.headers.toMultimap().mapValues { (_, v) -> v.firstOrNull() ?: "" },
            )
        }
    }

    /** First value of a JSON path, e.g. jsonPath(body, "plan", "name"). */
    private fun jsonPath(body: String, vararg keys: String): String? = runCatching {
        var el: kotlinx.serialization.json.JsonElement = Json.parseToJsonElement(body)
        for (key in keys) {
            el = el.jsonObject[key] ?: return null
        }
        (el as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    }.getOrNull()

    /** First "full_name" of a JSON array body (repo listing). */
    private fun firstRepoFullName(body: String): String? = runCatching {
        Json.parseToJsonElement(body).jsonArray.firstOrNull()
            ?.jsonObject?.get("full_name")?.jsonPrimitive?.content
    }.getOrNull()

    private fun checkApiUser(lines: MutableList<String>, token: String) {
        val resp = runCatching { api("/user", token) }.getOrElse {
            lines += "[fail] API ping: request failed (${it.message})"
            return
        }
        when {
            resp.code == 401 -> {
                lines += "[fail] API ping: token rejected (401). GitHub says: " +
                    (jsonPath(resp.body, "message") ?: "invalid or expired token")
                return
            }
            resp.code != 200 -> {
                lines += "[fail] API ping: HTTP ${resp.code}. GitHub says: " +
                    (jsonPath(resp.body, "message") ?: "(no message)")
                return
            }
        }
        val login = jsonPath(resp.body, "login") ?: "?"
        val type = jsonPath(resp.body, "type") ?: "?"
        val plan = jsonPath(resp.body, "plan", "name")
        lines += "[ok] API: authenticated as $login ($type${plan?.let { ", plan: $it" } ?: ""})"

        val scopes = resp.header("X-OAuth-Scopes")?.trim().orEmpty()
        if (scopes.isEmpty()) {
            lines += "[warn] scopes: not reported (fine-grained PATs do not list them via this header); " +
                "verify the token's permissions on github.com/settings/personal-access-tokens"
        } else {
            val granted = scopes.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            lines += "[ok] scopes: $scopes"
            SCOPE_CONSEQUENCES.forEach { (scope, consequence) ->
                if (scope !in granted) {
                    lines += "[warn] scope `$scope` missing: $consequence (re-create the token with it)"
                }
            }
        }
    }

    private suspend fun checkTokenFile(lines: MutableList<String>, ctx: ToolContext) {
        val cwd = ctx.workspace.shellRoot ?: linuxEnv.shellFallbackRoot
        val tier = router.resolveTier(cwd)
        val probeRoot = EnvProbes.probeRoot(linuxEnv, shizuku, tier)
        val mode = EnvProbes.fileMode(shizuku, probeRoot, GitHubProvision.TOKEN_FILE)
        val path = File(probeRoot, GitHubProvision.TOKEN_FILE).absolutePath
        when (mode) {
            "600" -> lines += "[ok] token file: $path is 0600"
            null -> lines += "[warn] token file: $path not readable from this tier (mode unknown)"
            else -> lines += "[fail] token file: $path is $mode; should be 0600 " +
                "(redeploy or re-save the token in Settings → GitHub to repair)"
        }
    }

    // --- git transport ------------------------------------------------------

    private suspend fun checkGitTransport(lines: MutableList<String>, cwd: File) {
        if (!linuxEnv.isReady) {
            lines += "[warn] git transport: toolchain not installed, nothing to verify"
            return
        }
        val script = buildString {
            append("sp=\"\$(git var GIT_SHELL_PATH 2>/dev/null)\"; ")
            append("case \"\$sp\" in ")
            append("\"\") echo 'shell-path=FAIL (git var GIT_SHELL_PATH empty; git >= 2.30 required)' ;; ")
            append("*) if [ -x \"\$sp\" ]; then echo \"shell-path=OK \$sp\"; ")
            append("else echo \"shell-path=DEAD \$sp is not executable\"; fi ;; esac; ")
            append("io=\"\$(git config --global --get-regexp '^url\\..*insteadof\$' 2>/dev/null)\"; ")
            append("if [ -n \"\$io\" ]; then echo 'instead-of=OK'; else echo 'instead-of=ABSENT'; fi")
        }
        val res = runCatching { router.run(script, cwd, timeoutMs = 15_000, maxOutput = 4_000) }.getOrNull()
        val out = res?.rawOutput.orEmpty()
        val shellLine = out.lineSequence().firstOrNull { it.startsWith("shell-path=") }
        val insteadLine = out.lineSequence().firstOrNull { it.startsWith("instead-of=") }
        when {
            shellLine == null ->
                lines += "[warn] git transport: probe did not run (exit " +
                    "${res?.exitCode ?: -1})${res?.rawStderr?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
            shellLine.startsWith("shell-path=OK") ->
                lines += "[ok] git shell path: ${shellLine.removePrefix("shell-path=OK ")} is exec-able; " +
                    "git can spawn helpers/hooks (gh repo clone's credential helper works)"
            else ->
                lines += "[fail] git shell path: ${shellLine.removePrefix("shell-path=")}; helpers, hooks " +
                    "and aliases that spawn `sh` cannot run"
        }
        when (insteadLine?.removePrefix("instead-of=")) {
            "OK" -> lines += "[ok] git token rewrite: url.*.insteadOf present in GIT_CONFIG_GLOBAL"
            else -> lines += "[warn] git token rewrite: no insteadOf entry found; plain https://github.com " +
                "URLs are anonymous unless a credential helper provides auth"
        }
    }

    // --- Free-plan trap -----------------------------------------------------

    /**
     * Rulesets/branch-protection return 403 ("Upgrade to GitHub Pro") for
     * private repos on free accounts. Nothing else in the harness errors,
     * the repo just silently has NO protection and force-pushes succeed, so
     * the paywall must be surfaced explicitly. Checks the user's most recently
     * pushed private repo, then one org's.
     */
    private fun checkFreePlanTrap(lines: MutableList<String>, token: String) {
        // visibility + affiliation is the combinable pair; `type` alongside
        // `affiliation` is rejected by GitHub with 422 Validation Failed,
        // which used to leave the probe permanently "unknown".
        val repos = runCatching {
            api("/user/repos?visibility=private&affiliation=owner&per_page=1&sort=pushed", token)
        }.getOrNull()
        if (repos == null || repos.code != 200) {
            lines += "[warn] free-plan probe: could not list private repos (HTTP ${repos?.code ?: -1}); " +
                "protection state unknown"
        } else {
            val repo = firstRepoFullName(repos.body)
            if (repo == null) {
                lines += "[ok] free-plan probe: no private repos to protect"
            } else {
                probeRulesets(lines, token, repo)
            }
        }
        val orgs = runCatching { api("/user/orgs?per_page=1", token) }.getOrNull()
        val org = orgs?.takeIf { it.code == 200 }?.let { jsonPath(it.body, "login") }
        if (org != null) {
            val orgRepo = runCatching {
                api("/orgs/$org/repos?type=private&per_page=1&sort=pushed", token)
            }.getOrNull()
            val orgFullName = orgRepo?.takeIf { it.code == 200 }?.let { firstRepoFullName(it.body) }
            when {
                orgFullName != null -> probeRulesets(lines, token, orgFullName)
                else -> lines += "[warn] org probe: no private $org repo visible to this token; " +
                    "that repo's protection state is unchecked"
            }
        }
    }

    private fun probeRulesets(lines: MutableList<String>, token: String, repo: String) {
        val resp = runCatching { api("/repos/$repo/rulesets", token) }.getOrNull()
        // Label the cited repo: it is whatever private repo happened to be
        // pushed most recently, NOT a dedicated probe target (doctor is
        // GET-only and never touches anyone's repos).
        when {
            resp == null -> lines += "[warn] rulesets probe failed (network)"
            resp.code == 200 -> lines += "[ok] rulesets: available for private repos; checked against " +
                "$repo (first recently-pushed private repo found; GET-only)"
            resp.code == 403 -> lines += "[warn] FREE-PLAN TRAP: rulesets/branch protection is paywalled " +
                "for private repos: $repo (the first recently-pushed private repo found; GET-only) " +
                "returned 403: ${jsonPath(resp.body, "message") ?: "upgrade required"}; those branches are " +
                "UNPROTECTED and force-pushes succeed. Real protection needs GitHub Pro / Team / org rulesets."
            else -> lines += "[warn] rulesets probe for $repo: HTTP ${resp.code} " +
                "(${jsonPath(resp.body, "message") ?: "no message"})"
        }
    }

    private companion object {
        val SCOPE_CONSEQUENCES = listOf(
            "delete_repo" to "the agent cannot delete repos it created via gh (403 on DELETE /repos)",
            "gist" to "gist creation/management via gh fails (404/403)",
            "read:org" to "org-owned repos and org membership are invisible (404)",
            "workflow" to "pushes touching GitHub Actions workflow files are rejected",
        )
    }
}
