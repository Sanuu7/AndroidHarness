package com.androidharness.app.tools

import com.androidharness.app.phone.PhoneController
import kotlinx.serialization.json.*

class PhoneTool(private val controller: PhoneController) : Tool {
    override val name = "phone_control"
    override val description = "Control the user's Android phone with a visible mouse, only after the user enables Phone control for this chat. Actions: status, screenshot, move, click, drag, scroll, type, key. Always inspect a fresh screenshot before acting. Coordinates are physical screen pixels, not resized image pixels. Scroll text is -10..10 (nonzero); key text is BACK/HOME/ENTER/DEL/TAB/APP_SWITCH. Typing supports printable ASCII only. Ask before purchases, messages, deletion or other consequential actions. Never enter credentials or bypass secure screens."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(mapOf(
        "action" to Schema.string("Action to perform"),
        "x" to Schema.integer("Mouse x"), "y" to Schema.integer("Mouse y"),
        "x2" to Schema.integer("Drag destination x"), "y2" to Schema.integer("Drag destination y"),
        "text" to Schema.string("Text, key name or scroll amount"),
    ), listOf("action"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        fun number(key: String) = args[key]?.jsonPrimitive?.intOrNull ?: -1
        return controller.execute(ctx.sessionId, args["action"]?.jsonPrimitive?.content.orEmpty(), number("x"), number("y"), number("x2"), number("y2"), args["text"]?.jsonPrimitive?.content.orEmpty())
    }
}
