package com.androidharness.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal object ProjectLaunch {
    fun scripts(packageJson: String): List<String> = runCatching {
        Json.parseToJsonElement(packageJson).jsonObject["scripts"]?.jsonObject?.keys.orEmpty()
            .filter { it.matches(Regex("[A-Za-z0-9:_-]{1,80}")) }
            .sortedWith(compareBy<String> { when (it) { "dev" -> 0; "start" -> 1; "preview" -> 2; else -> 3 } }.thenBy { it })
    }.getOrDefault(emptyList())

    fun npmCommand(script: String): String {
        require(script.matches(Regex("[A-Za-z0-9:_-]{1,80}")))
        return "npm run '$script'"
    }

    fun prepare(command: String, npm: Boolean): String = if (!npm) command else
        "command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 || { printf '%s\\n' 'Node.js and npm are required. Set up the runtime before running this project.'; exit 127; }; $command"

    fun previewUrl(output: String): String? {
        val clean = output.replace(Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
        return Regex("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1\\]):([0-9]{1,5})(?:/[^\\s<>\"']*)?")
            .findAll(clean).lastOrNull { (it.groupValues[1].toIntOrNull() ?: 0) in 1..65535 }
            ?.value?.replace("0.0.0.0", "127.0.0.1")?.replace("[::1]", "localhost")
    }
}
