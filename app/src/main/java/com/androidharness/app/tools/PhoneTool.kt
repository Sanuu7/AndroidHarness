package com.androidharness.app.tools

import com.androidharness.app.phone.PhoneController
import kotlinx.serialization.json.*

class PhoneTool(private val controller: PhoneController) : Tool {
    override val name = "phone_control"
    override val description = "Control the user's Android phone with a visible mouse. Calling this tool prompts the user to allow phone control if not already active. Actions: status, screenshot, move, click, drag, scroll, type, key. Each input requires a new screenshot of the same focused window. Inspect after every key or tap; do not batch blind taps. Dismiss visible overlays only after inspecting them. If capture ends, request screenshot to restart the consent flow; respect cancellation. Coordinates are physical screen pixels. If using a resized full-screen image, provide screenshot_width and screenshot_height for automatic scaling. Never use cropped-image coordinates. Scroll text is -10..10 (nonzero); key text is BACK/HOME/ENTER/DEL/TAB/APP_SWITCH. Typing supports printable ASCII only. Ask before purchases, messages, deletion or other consequential actions. Never enter credentials or bypass secure screens."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(mapOf(
        "action" to Schema.string("Action to perform"),
        "x" to Schema.integer("Mouse x"), "y" to Schema.integer("Mouse y"),
        "x2" to Schema.integer("Drag destination x"), "y2" to Schema.integer("Drag destination y"),
        "screenshot_width" to Schema.integer("Width of the full screenshot used for coordinates, if resized"),
        "screenshot_height" to Schema.integer("Height of the full screenshot used for coordinates, if resized"),
        "text" to Schema.string("Text, key name or scroll amount"),
    ), listOf("action"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        fun number(key: String) = args[key]?.jsonPrimitive?.intOrNull ?: -1
        return controller.execute(ctx.sessionId, args["action"]?.jsonPrimitive?.content.orEmpty(), number("x"), number("y"), number("x2"), number("y2"), args["text"]?.jsonPrimitive?.content.orEmpty(), number("screenshot_width"), number("screenshot_height"))
    }
}
