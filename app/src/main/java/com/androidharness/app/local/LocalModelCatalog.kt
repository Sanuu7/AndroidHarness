package com.androidharness.app.local

import kotlinx.serialization.Serializable

const val GIB = 1_073_741_824L

data class LocalModelSpec(
    val id: String,
    val title: String,
    val repository: String,
    val revision: String,
    val filename: String,
    val bytes: Long,
    val sha256: String,
    val minimumRamGiB: Int,
    val kvBytesPerToken: Long,
    val description: String,
    val license: String = "Apache-2.0",
) {
    val url get() = "https://huggingface.co/$repository/resolve/$revision/$filename"
    val modelPage get() = "https://huggingface.co/$repository"
    fun estimatedMemory(context: Int): Long = bytes + 640L * 1024 * 1024 + context * kvBytesPerToken
}

object LocalModelCatalog {
    val models = listOf(
        LocalModelSpec("qwen-05b", "Qwen 2.5 0.5B", "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            "9217f5db79a29953eb74d5343926648285ec7e67", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491400032, "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            3, 24576, "Small download, basic chat. Limited coding ability."),
        LocalModelSpec("qwen-15b", "Qwen 2.5 1.5B", "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            "91cad51170dc346986eccefdc2dd33a9da36ead9", "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1117320736, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            4, 28672, "Better general chat. CPU speed depends on your phone."),
        LocalModelSpec("qwen-coder-15b", "Qwen 2.5 Coder 1.5B", "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            "f86cb2c1fa58255f8052cc32aeede1b7482d4361", "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            1117320768, "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046",
            4, 28672, "Code explanations and small snippets. Text chat, not autonomous tools."),
        LocalModelSpec("qwen-3b", "Qwen 2.5 3B", "Qwen/Qwen2.5-3B-Instruct-GGUF",
            "7dabda4d13d513e3e842b20f0d435c732f172cbe", "qwen2.5-3b-instruct-q4_k_m.gguf",
            2104932768, "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            6, 73728, "Higher quality, larger download and more heat.", "Qwen Research License"),
    )
    fun find(id: String) = models.firstOrNull { it.id == id }
    const val PROVIDER_PREFIX = "local-model:"
    fun isLocal(id: String) = id.startsWith(PROVIDER_PREFIX)
}

@Serializable
data class LocalModelLimits(
    val context: Int = 2048,
    val input: Int = 1536,
    val output: Int = 512,
    val threads: Int = 4,
) {
    fun validate() {
        require(context in 512..8192) { "Context must be 512 to 8192 tokens." }
        require(input >= 128 && output in 16..4096) { "Input must be at least 128; output must be 16 to 4096 tokens." }
        require(input.toLong() + output <= context) { "Input plus output must fit inside context." }
        require(threads in 1..8) { "CPU threads must be 1 to 8." }
    }
}

data class LocalDeviceProfile(
    val totalRam: Long,
    val availableRam: Long,
    val freeStorage: Long,
    val abi: String,
    val cores: Int,
    val lowMemory: Boolean,
) {
    val supported get() = abi == "arm64-v8a" || abi == "x86_64"
    val rank get() = when {
        !supported -> "Unsupported architecture"
        totalRam < 3 * GIB -> "Limited memory"
        totalRam < 4 * GIB -> "Basic"
        totalRam < 6 * GIB -> "Balanced"
        else -> "Higher memory"
    }
    fun fits(model: LocalModelSpec, context: Int = 2048): Boolean =
        supported && totalRam >= model.minimumRamGiB * GIB * 9 / 10 &&
            model.estimatedMemory(context) <= totalRam * 55 / 100
    fun canLoad(model: LocalModelSpec, context: Int): Boolean = fits(model, context) &&
        !lowMemory && availableRam >= model.estimatedMemory(context) + 256L * 1024 * 1024
}
