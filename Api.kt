package com.md.assistant
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    private fun call(base: String, path: String, token: String, body: JSONObject): JSONObject {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout = 90000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (token.isNotBlank()) c.setRequestProperty("x-app-token", token)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        if (code >= 400) error("HTTP $code " + runCatching { JSONObject(txt).optString("error") }.getOrDefault(""))
        return JSONObject(txt)
    }

    fun command(base: String, token: String, text: String): Action =
        Router.fromJson(call(base, "/api/command", token, JSONObject().put("text", text)))

    /** Returns (chat message, absolute download URL). */
    fun presentation(base: String, token: String, a: Action): Pair<String, String> {
        val o = call(base, "/api/presentation", token,
            JSONObject().put("topic", a.topic).put("slides", a.slides).put("audience", a.audience))
        val arr = o.getJSONArray("slides")
        val list = (0 until arr.length()).joinToString("\n") { "${it + 2}. ${arr.getJSONObject(it).optString("title")}" }
        return Pair("Created \"${o.optString("title")}\" (validated PPTX).\n1. Title\n$list\nOpening download…",
            base.trimEnd('/') + o.getString("downloadUrl"))
    }
}
