package com.dominion.assistant.voice

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.os.Bundle

/**
 * Voice-role recognition adapter. Delegates to a distinct Android speech provider.
 * Never treats itself as its own delegate, avoiding recursive recognition services.
 * No independent wake word capture is started here.
 */
class DominionRecognitionService : RecognitionService() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private fun delegate(): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        }
        val candidates = packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE), 0
        )
        val candidate = candidates.firstOrNull {
            it.serviceInfo?.packageName != packageName && it.serviceInfo?.exported == true
        }?.serviceInfo ?: return null
        return SpeechRecognizer.createSpeechRecognizer(
            this, ComponentName(candidate.packageName, candidate.name)
        )
    }

    override fun onStartListening(intent: Intent, callback: Callback) {
        handler.post {
            try {
                recognizer?.destroy()
                recognizer = delegate()
                val speech = recognizer ?: run {
                    callback.error(SpeechRecognizer.ERROR_CLIENT)
                    return@post
                }
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        callback.readyForSpeech(params ?: Bundle())
                    }
                    override fun onBeginningOfSpeech() { callback.beginningOfSpeech() }
                    override fun onRmsChanged(rmsdB: Float) { callback.rmsChanged(rmsdB) }
                    override fun onBufferReceived(buffer: ByteArray?) {
                        if (buffer != null) callback.bufferReceived(buffer)
                    }
                    override fun onEndOfSpeech() { callback.endOfSpeech() }
                    override fun onError(error: Int) { callback.error(error) }
                    override fun onResults(results: Bundle?) { callback.results(results ?: Bundle()) }
                    override fun onPartialResults(partialResults: Bundle?) {
                        callback.partialResults(partialResults ?: Bundle())
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {
                        // RecognitionService.Callback exposes no event passthrough.
                    }
                })
                speech.startListening(intent)
            } catch (_: Exception) {
                callback.error(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    override fun onStopListening(callback: Callback) {
        handler.post { recognizer?.stopListening() }
    }

    override fun onCancel(callback: Callback) {
        handler.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    override fun onDestroy() {
        handler.post {
            recognizer?.destroy()
            recognizer = null
        }
        super.onDestroy()
    }
}
