package com.dominion.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Foreground, user-triggered dictation and Android TTS. Not an always-on call. */
class NativeVoice(private val context: Context) : TextToSpeech.OnInitListener {
    private var recognition: SpeechRecognizer? = null
    private val tts = TextToSpeech(context, this)
    private var ready = false
    var listening by mutableStateOf(false)
        private set
    var status by mutableStateOf("음성 대기")
        private set

    override fun onInit(code: Int) {
        ready = code == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.KOREAN
    }

    fun listen(onText: (String) -> Unit) {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = "이 기기에서 음성 인식을 사용할 수 없습니다."
            return
        }
        stopListening()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognition = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status = "말씀해 주세요" }
            override fun onBeginningOfSpeech() { status = "듣고 있습니다" }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status = "음성을 확인하는 중" }
            override fun onError(error: Int) {
                status = "음성 인식 오류 ($error)"
                stopListening()
            }
            override fun onResults(results: Bundle?) {
                val recognized = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                stopListening()
                status = if (recognized.isBlank()) "말씀을 인식하지 못했습니다." else "음성 입력 완료"
                if (recognized.isNotBlank()) onText(recognized)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            tts.stop()
            listening = true
            recognizer.startListening(intent)
        } catch (_: Exception) {
            status = "음성 서비스를 시작할 수 없습니다."
            stopListening()
        }
    }

    fun stopListening() {
        recognition?.destroy()
        recognition = null
        listening = false
    }

    fun speak(value: String) {
        if (!ready) {
            status = "음성 출력 엔진 준비 중"
            return
        }
        tts.speak(value.take(1800), TextToSpeech.QUEUE_FLUSH, null, "dominion-reply")
        status = "답변을 읽고 있습니다"
    }

    fun interrupt() {
        tts.stop()
        status = "음성 중지"
    }

    fun close() {
        stopListening()
        tts.stop()
        tts.shutdown()
    }
}
