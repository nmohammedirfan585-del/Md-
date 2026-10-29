package com.md.assistant
import org.json.JSONObject

data class Action(val type: String, val app: String = "", val query: String = "", val command: String = "",
                  val text: String = "", val topic: String = "", val slides: Int = 10,
                  val audience: String = "college", val confirm: Boolean = false)

object Router {
    private val types = setOf("open_app", "search", "media", "navigate", "chat", "presentation", "unsupported")
    private val media = setOf("play", "pause", "next", "previous", "stop")

    /** Every action (local or from the AI) passes through here before execution. */
    fun validate(a: Action): Action = when {
        a.type !in types -> Action("unsupported", text = "Unknown action blocked.")
        a.type == "media" && a.command !in media -> Action("unsupported", text = "Unsupported media command.")
        a.type == "navigate" && a.command !in setOf("home", "back") -> Action("unsupported", text = "Unsupported navigation.")
        a.type == "presentation" -> a.copy(slides = a.slides.coerceIn(3, 20), confirm = true)
        else -> a
    }

    fun fromJson(o: JSONObject) = Action(o.optString("type"), o.optString("app"), o.optString("query"),
        o.optString("command"), o.optString("text"), o.optString("topic"), o.optInt("slides", 10),
        o.optString("audience", "college").ifBlank { "college" }, o.optBoolean("confirm"))

    /** Offline command parser: works with no server and no API key. */
    fun local(raw: String): Action? {
        val t = raw.lowercase().trim().replace(Regex("^(hey |ok )?md[,.:]?\\s*"), "").trimEnd('.', '!', '?').trim()
        if (Regex("powerpoint|presentation|\\bppt\\b").containsMatchIn(t) && Regex("create|make|generate|build").containsMatchIn(t)) {
            val topic = Regex("\\b(?:about|on)\\s+(.+?)(?:\\s+for\\s+.+)?$").find(t)?.groupValues?.get(1) ?: "General topic"
            val n = Regex("(\\d+)[- ]slide").find(t)?.groupValues?.get(1)?.toInt() ?: 10
            val aud = Regex("\\bfor (?:my |the )?(.+)$").find(t)?.groupValues?.get(1) ?: "college"
            return Action("presentation", topic = topic, slides = n, audience = aud, confirm = true)
        }
        if (Regex("^(?:go )?(?:to )?(?:the )?home(?: screen)?$").matches(t)) return Action("navigate", command = "home")
        if (Regex("^(?:go )?back$").matches(t)) return Action("navigate", command = "back")
        if (Regex("^(pause|stop)( the)?( music| song| video)?$").matches(t)) return Action("media", command = "pause")
        if (Regex("^(resume|continue|play)( the)?( music| song| video)?$").matches(t)) return Action("media", command = "play")
        if (Regex("^(next|skip)( song| track)?$").matches(t)) return Action("media", command = "next")
        if (Regex("^(previous|last)( song| track)?$").matches(t)) return Action("media", command = "previous")
        Regex("^(?:open|launch|start) (?:the )?(.+?)(?: app)?$").find(t)?.let { return Action("open_app", app = it.groupValues[1]) }
        Regex("^search youtube for (.+)$").find(t)?.let { return Action("search", app = "youtube", query = it.groupValues[1]) }
        Regex("^play (.+?)(?: on youtube)?$").find(t)?.let { return Action("search", app = "youtube", query = it.groupValues[1]) }
        Regex("^(?:search|google)(?: google)?(?: for)? (.+)$").find(t)?.let { return Action("search", app = "google", query = it.groupValues[1]) }
        return null
    }
}
