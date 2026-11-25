package com.dominion.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dominion.assistant.MainActivity

/** OS assistant registration. No automatic hotwording or background microphone. */
class DominionVoiceService : VoiceInteractionService()

class DominionVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle): VoiceInteractionSession =
        DominionVoiceSession(this)
}

class DominionVoiceSession(private val serviceContext: Context) :
    VoiceInteractionSession(serviceContext) {
    override fun onCreateContentView(): View {
        val container = LinearLayout(serviceContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        container.addView(TextView(serviceContext).apply {
            text = "DOMINION Assistant"
            textSize = 22f
        })
        container.addView(Button(serviceContext).apply {
            text = "비서 열기"
            setOnClickListener {
                serviceContext.startActivity(
                    Intent(serviceContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }
        })
        return container
    }
}
