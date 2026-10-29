package com.md.assistant
import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object Store {
    fun load(p: SharedPreferences): List<Pair<String, String>> {
        val a = JSONArray(p.getString("h", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("r") to o.getString("t") } }
    }
    fun save(p: SharedPreferences, m: List<Pair<String, String>>) {
        val a = JSONArray(); m.takeLast(200).forEach { a.put(JSONObject().put("r", it.first).put("t", it.second)) }
        p.edit().putString("h", a.toString()).apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { MdApp(this) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MdApp(ctx: Context) {
    val prefs = remember { ctx.getSharedPreferences("md", Context.MODE_PRIVATE) }
    val msgs = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(Store.load(prefs)) } }
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Action?>(null) }
    var server by remember { mutableStateOf(prefs.getString("server", "http://10.0.2.2:3000")!!) }
    var token by remember { mutableStateOf(prefs.getString("token", "")!!) }
    var speak by remember { mutableStateOf(prefs.getBoolean("speak", true)) }
    var useAi by remember { mutableStateOf(prefs.getBoolean("ai", true)) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val cb = remember { VoiceCb() }
    val voice = remember { Voice(ctx, cb) }
    DisposableEffect(Unit) { onDispose { voice.release() } }

    fun add(role: String, text: String) {
        msgs.add(role to text); Store.save(prefs, msgs)
        if (role == "md" && speak) voice.speak(text)
    }
    fun execute(a: Action) {
        scope.launch {
            if (a.type == "presentation") {
                add("md", "Generating your presentation…")
                val r = withContext(Dispatchers.IO) { runCatching { Api.presentation(server, token, a) } }
                r.onSuccess { add("md", it.first); Executor.openUrl(ctx, it.second) }
                    .onFailure { add("md", "Couldn't generate the presentation: ${it.message}. Is the backend running and reachable at $server?") }
            } else add("md", Executor.run(ctx, a))
        }
    }
    fun handle(text: String) {
        add("you", text)
        scope.launch {
            var a = Router.local(text)
            if (a == null) a = if (useAi) withContext(Dispatchers.IO) {
                runCatching { Api.command(server, token, text) }.getOrElse { Action("chat", text = "Couldn't reach the AI server (${it.message}). Built-in commands still work.") }
            } else Action("unsupported", text = "I didn't understand. AI mode is off; try \"open YouTube\".")
            val v = Router.validate(a)
            if (v.confirm) { pending = v; add("md", "Confirm: create a ${v.slides}-slide presentation about \"${v.topic}\"?") } else execute(v)
        }
    }
    cb.text = { handle(it) }; cb.state = { listening = it }; cb.error = { add("md", it) }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) voice.listen() else add("md", "Microphone permission denied. You can still type commands.")
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        TopAppBar(title = { Text("MD") }, actions = { TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Chat" else "Settings") } })
        if (showSettings) {
            Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(server, { server = it; prefs.edit().putString("server", it).apply() }, label = { Text("Backend URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it; prefs.edit().putString("token", it).apply() }, label = { Text("App token (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Spoken replies", Modifier.weight(1f)); Switch(speak, { speak = it; prefs.edit().putBoolean("speak", it).apply() }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Use AI server for unknown commands", Modifier.weight(1f)); Switch(useAi, { useAi = it; prefs.edit().putBoolean("ai", it).apply() }) }
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Enable Accessibility Service (for Back)") }
                OutlinedButton(onClick = { msgs.clear(); Store.save(prefs, msgs) }) { Text("Clear history") }
            }
        } else {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (msgs.isEmpty()) item { Text("Try: \"open YouTube\", \"play lo-fi on YouTube\", \"search Google for physics notes\", \"create a 10-slide PowerPoint about AI\".", Modifier.padding(8.dp)) }
                items(msgs) { (r, t) ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (r == "you") Alignment.CenterEnd else Alignment.CenterStart) {
                        Surface(shape = RoundedCornerShape(16.dp), color = if (r == "you") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Text(t, Modifier.padding(12.dp).widthIn(max = 300.dp))
                        }
                    }
                }
            }
            if (listening) Text("● Listening…", Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.error)
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Type a command") }, singleLine = true)
                Button(onClick = { if (input.isNotBlank()) { handle(input.trim()); input = "" } }) { Text("Send") }
                FilledTonalButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.listen()
                    else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("🎤") }
            }
        }
    }
    pending?.let { a ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Confirm") },
            text = { Text("Create a ${a.slides}-slide presentation about \"${a.topic}\" for ${a.audience}?") },
            confirmButton = { TextButton(onClick = { pending = null; execute(a) }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { pending = null; add("md", "Cancelled.") }) { Text("Cancel") } })
    }
}
