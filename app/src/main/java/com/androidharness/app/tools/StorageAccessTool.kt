package com.androidharness.app.tools

import com.androidharness.app.core.StorageAccessReceipt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lets the agent put a storage access card in the chat. The card never grants anything: it holds
 * buttons that open Android's own pages, and it shows a check once access exists.
 */
internal class StorageAccessTool(private val granted: () -> Boolean) : Tool {
    override val name = NAME
    override val description = "Show the user a card that explains why you need access to shared device storage (Download, Documents, DCIM, an SD card) " +
        "and has buttons to open Android's All files access page and the app's permission settings. Use it when a storage tool was blocked, " +
        "or when the user turned storage access off and the task needs it. Does nothing if access is already granted. " +
        "The user decides; stop and wait for them to say it is granted instead of retrying."
    override val parametersSchema = Schema.obj(
        mapOf(
            "reason" to Schema.string("One plain sentence for the user: what you need to do and why it needs storage access."),
            "path" to Schema.string("The shared-storage location you tried to reach, such as /sdcard/Download."),
        ),
        required = listOf("reason"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (granted()) return ToolResult(true, "Storage access is already granted. No card was shown. Continue with the task.")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (reason.isEmpty()) throw ToolFailure("Missing required argument: reason")
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return ToolResult(
            true,
            "Showed the user a storage access card. They choose whether to grant it. Tell them in one line what you are waiting for, " +
                "and stop. Do not retry the blocked step or use another route into shared storage until the user says access is granted.",
            storageAccess = StorageAccessReceipt(reason.take(400), path?.take(300)),
        )
    }

    companion object { const val NAME = "request_storage_access" }
}
