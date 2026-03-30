package com.foursightai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * VoiceManager — single responsibility: TTS output + voice command input.
 */
class VoiceManager(
    private val context: Context,
    private val onCommandReceived: (String) -> Unit
) : TextToSpeech.OnInitListener, RecognitionListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isTtsReady = false
    private var isListening = false
    private var shouldRestartListening = false

    // TTS cooldown
    private var lastSpokenTime = 0L
    private val SPEECH_COOLDOWN_MS = 3500L

    // Last spoken text — for "repeat" command
    private var lastSpokenText: String = ""

    init {
        tts = TextToSpeech(context, this)
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(this)
        } else {
            Log.e("VoiceManager", "Speech recognition not available on this device")
        }
    }

    // ─── TTS ─────────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("VoiceManager", "TTS language not supported")
            } else {
                isTtsReady = true
                Log.i("VoiceManager", "TTS ready")
            }
        }
    }

    /**
     * Speak text through TTS.
     * @param text    The string to speak
     * @param flush   true = interrupt any current speech, false = queue behind it
     * @param force   true = bypass cooldown (use for critical STOP alerts)
     */
    fun speak(text: String, flush: Boolean = true, force: Boolean = false) {
        if (!isTtsReady) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSpokenTime < SPEECH_COOLDOWN_MS) return

        lastSpokenTime = now
        lastSpokenText = text
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, "nav_${now}")
    }

    fun repeatLastInstruction() {
        if (lastSpokenText.isNotEmpty()) {
            tts?.speak(lastSpokenText, TextToSpeech.QUEUE_FLUSH, null, "repeat_${System.currentTimeMillis()}")
        }
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun stopSpeaking() { tts?.stop() }

    // ─── Speech recognition ───────────────────────────────────────────────────

    fun startListening() {
        if (isListening) return
        shouldRestartListening = true
        launchRecognizer()
    }

    fun stopListening() {
        shouldRestartListening = false
        speechRecognizer?.stopListening()
        isListening = false
    }

    private fun launchRecognizer() {
        if (!shouldRestartListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            Log.e("VoiceManager", "startListening failed: ${e.message}")
        }
    }

    // ─── RecognitionListener callbacks ────────────────────────────────────────

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val text = matches[0].lowercase(Locale.US).trim()
            Log.d("VoiceManager", "Recognised: $text")

            // Map raw speech to canonical command tokens
            val command = when {
                "stop" in text && "navigation" in text -> "stop navigation"
                "describe" in text || "surroundings" in text || "around" in text -> "describe surroundings"
                "ahead" in text || "front" in text || "what" in text -> "whats ahead"
                "repeat" in text || "again" in text -> "repeat"
                "clear" in text -> "path clear"
                else -> text
            }
            onCommandReceived(command)
        }
        // Auto-restart for continuous listening
        launchRecognizer()
    }

    override fun onError(error: Int) {
        isListening = false
        Log.w("VoiceManager", "Recognition error code: $error")
        // Restart unless it's a fatal error
        if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            launchRecognizer()
        }
    }

    override fun onEndOfSpeech() { isListening = false }

    // Unused but required by interface
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun shutdown() {
        shouldRestartListening = false
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }
}
