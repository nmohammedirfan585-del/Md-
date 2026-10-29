package com.md.assistant
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.app.SearchManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import android.accessibilityservice.AccessibilityService

object Executor {
    private val known = mapOf("youtube" to "com.google.android.youtube", "whatsapp" to "com.whatsapp",
        "chrome" to "com.android.chrome", "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music", "instagram" to "com.instagram.android", "telegram" to "org.telegram.messenger")

    private fun start(c: Context, i: Intent): Boolean = try {
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

    fun run(c: Context, a: Action): String = when (a.type) {
        "open_app" -> openApp(c, a.app)
        "search" -> if (a.app == "youtube") youtube(c, a.query) else web(c, a.query)
        "media" -> media(c, a.command)
        "navigate" -> nav(c, a.command)
        "chat" -> a.text
        else -> a.text.ifBlank { "I can't do that on Android without extra permission or support." }
    }

    fun openUrl(c: Context, url: String) = start(c, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun openApp(c: Context, raw: String): String {
        val name = raw.lowercase().trim()
        val sys = when {
            name.startsWith("setting") -> Settings.ACTION_SETTINGS
            name.contains("wifi") || name.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            name.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            else -> null
        }
        if (sys != null) return if (start(c, Intent(sys))) "Opened ${raw.trim()}." else "Couldn't open that settings screen."
        val pm = c.packageManager
        val pkg = known[name] ?: pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }?.activityInfo?.packageName
        val intent = pkg?.let { pm.getLaunchIntentForPackage(it) }
            ?: return "I couldn't find an installed app called \"$raw\"."
        return if (start(c, intent)) "Opened $raw." else "Couldn't open $raw."
    }

    private fun youtube(c: Context, q: String): String {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
        val inApp = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube")
        if (start(c, inApp)) return "Opened YouTube search results for \"$q\". Tap a result to play (automatic playback needs the YouTube Data API)."
        return if (start(c, Intent(Intent.ACTION_VIEW, uri))) "YouTube app not found; opened results in your browser." else "Couldn't open YouTube."
    }

    private fun web(c: Context, q: String): String {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        if (start(c, i)) return "Searching Google for \"$q\"."
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
        return if (start(c, fallback)) "Searching Google for \"$q\"." else "Couldn't open a browser."
    }

    private fun media(c: Context, cmd: String): String {
        val key = when (cmd) { "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_STOP }
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return "Sent \"$cmd\" to the active media session. If nothing changed, the playing app doesn't respond to media keys."
    }

    private fun nav(c: Context, cmd: String): String {
        val svc = MdAccessibilityService.instance
        if (cmd == "home") {
            if (svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true) return "Went home."
            return if (start(c, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))) "Went home." else "Couldn't go home."
        }
        if (svc != null) return if (svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) "Went back." else "Back failed."
        start(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return "\"Back\" needs the MD Accessibility Service. I opened Accessibility settings: enable MD there, then try again."
    }
}
