package com.androidharness.app.local

import kotlinx.serialization.Serializable

const val GIB = 1_073_741_824L

@Serializable
enum class LocalModelFormat { GGUF, SAFETENSORS }

@Serializable
data class LocalModelAsset(val filename: String, val url: String, val bytes: Long, val sha256: String = "")

@Serializable
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
    val downloadUrl: String? = null,
    val sourcePage: String? = null,
    val custom: Boolean = false,
    val maxContext: Int = 8192,
    val format: LocalModelFormat = LocalModelFormat.GGUF,
    val assets: List<LocalModelAsset> = emptyList(),
    val projector: LocalModelAsset? = null,
    val localFile: Boolean = false,
    val abliterated: Boolean = false,
) {
    val url get() = downloadUrl ?: "https://huggingface.co/$repository/resolve/$revision/$filename"
    val modelPage get() = sourcePage ?: "https://huggingface.co/$repository"
    val defaultContext get() = minOf(2048, maxContext)
    val downloadBytes get() = (if (assets.isEmpty()) bytes else assets.sumOf { it.bytes }) + (projector?.bytes ?: 0)
    fun estimatedMemory(context: Int): Long = bytes + (projector?.bytes ?: 0) + 640L * 1024 * 1024 + context.toLong() * kvBytesPerToken
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
            4, 28672, "Coding help and small changes. Tool reliability depends on the task."),
        LocalModelSpec("qwen-3b", "Qwen 2.5 3B", "Qwen/Qwen2.5-3B-Instruct-GGUF",
            "7dabda4d13d513e3e842b20f0d435c732f172cbe", "qwen2.5-3b-instruct-q4_k_m.gguf",
            2104932768, "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            6, 73728, "Higher quality, larger download and more heat.", "Qwen Research License"),
    ) + LocalModelRecommendations.models
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
        require(context > 0) { "Context must be a positive number." }
        require(input > 0 && output > 0) { "Input and output must be positive numbers." }
        require(input.toLong() + output < Int.MAX_VALUE) { "The combined token budget exceeds the engine's addressable range." }
        require(threads > 0) { "CPU threads must be a positive number." }
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
    fun fits(model: LocalModelSpec, context: Int = model.defaultContext): Boolean =
        supported && context > 0 && totalRam >= model.minimumRamGiB * GIB * 9 / 10 &&
            model.estimatedMemory(context) <= totalRam * 55 / 100
    fun canLoad(model: LocalModelSpec, context: Int = model.defaultContext): Boolean = fits(model, context) &&
        !lowMemory && availableRam >= model.estimatedMemory(context) + 256L * 1024 * 1024
}

/** Estimates inform consent, never decide whether the user may try a model. */
fun localModelWarnings(model: LocalModelSpec, device: LocalDeviceProfile, limits: LocalModelLimits,
    downloading: Boolean = false): List<String> = buildList {
    if (!device.supported) add("This device architecture has no local engine. Downloading is possible, but inference may fail.")
    if (!device.canLoad(model, limits.context)) add("Estimated model memory exceeds the recommended budget or available RAM. Android may close the app, and generation may fail.")
    if (limits.context > model.maxContext) add("Context exceeds the model's trained length. Answers may degrade or the engine may fail.")
    if (limits.input.toLong() + limits.output > limits.context) add("Input plus output exceeds the saved context. The engine will offer to expand it when needed, using more memory.")
    if (limits.threads > device.cores) add("More threads than CPU cores may make generation slower and increase heat.")
    val storage = if (model.format == LocalModelFormat.SAFETENSORS) model.downloadBytes * 3 else model.downloadBytes
    if (downloading && device.freeStorage < storage + 256L * 1024 * 1024) add("Free storage may be insufficient for download${if (model.format == LocalModelFormat.SAFETENSORS) " and conversion" else ""}. The operation may fail.")
}
