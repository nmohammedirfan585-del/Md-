package com.md.assistant
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceCb { var text: (String) -> Unit = {}; var state: (Boolean) -> Unit = {}; var error: (String) -> Unit = {} }

class Voice(private val ctx: Context, private val cb: VoiceCb) {
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    init { tts = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() } }

    fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { cb.error("Speech recognition isn't available on this device. Use text input."); return }
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx); sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { cb.state(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { cb.state(false); cb.error("Voice error ($e). Try again or type your command.") }
            override fun onResults(b: Bundle?) {
                cb.state(false)
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) cb.text(t) else cb.error("I didn't catch that.")
            }
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }
    fun speak(t: String) { tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "md") }
    fun release() { sr?.destroy(); tts?.shutdown() }
}
