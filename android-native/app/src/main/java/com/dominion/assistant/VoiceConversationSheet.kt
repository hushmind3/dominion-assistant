package com.dominion.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dominion.assistant.data.AssistantProfile
import com.dominion.assistant.voice.CallStatus
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The call never ends when the conversation expands. The chat is a bottom overlay
 * measured against the WHOLE screen rather than the space left by a huge call header.
 * At 50%, nearly half the available screen is actually conversation; at 100%,
 * only one compact 64dp call-control row remains above the same chat composer.
 */
@Composable
fun VoiceConversationSheet(
    profile: AssistantProfile,
    call: CallStatus,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHangup: () -> Unit,
    chat: @Composable () -> Unit
) {
    var fraction by rememberSaveable(call.startedAt) { mutableFloatStateOf(0f) }
    var elapsed by remember(call.startedAt) { mutableIntStateOf(0) }
    LaunchedEffect(call.startedAt) {
        if (call.startedAt > 0) while (true) {
            elapsed = ((System.currentTimeMillis() - call.startedAt) / 1000)
                .toInt().coerceAtLeast(0)
            delay(1000)
        }
    }
    val accent = Color(0xFF9EE9D0)
    val secondary = Color(0xFFA4B5BD)
    val stateText = when (call.phase) {
        "connecting" -> "연결 중"
        "reconnecting" -> "다시 연결 중"
        "connected" -> if (call.botJoined) "듣고 있어요" else "비서가 연결되는 중"
        else -> call.message.ifBlank { "연결 중" }
    }
    val elapsedText = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    val toggle: () -> Unit = {
        fraction = when {
            fraction < .25f -> .5f
            fraction < .75f -> 1f
            else -> 0f
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF151F26), Color(0xFF0C141A)))
        )
    ) {
        val topRowHeight = 64.dp
        val handleHeight = 36.dp
        val sheetMaximum = (maxHeight - topRowHeight).coerceAtLeast(handleHeight)
        val sheetHeight = handleHeight + (sheetMaximum - handleHeight) * fraction
        val dragRange = with(LocalDensity.current) {
            (sheetMaximum - handleHeight).toPx().coerceAtLeast(1f)
        }

        Column(Modifier.fillMaxSize()) {
            // Always visible, even when chat uses the rest of the screen.
            Row(
                Modifier.fillMaxWidth().height(topRowHeight)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                AssistantAvatarView(profile, 38.dp)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(profile.name, color = Color(0xFFF3F7F8),
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                    Text("$elapsedText · $stateText",
                        color = secondary, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = onMute, modifier = Modifier.size(44.dp)) {
                    Icon(if (call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = if(call.muted) "마이크 켜기" else "음소거",
                        tint = if(call.muted) Color(0xFFFFBDB7) else accent)
                }
                IconButton(onClick = onSpeaker, modifier = Modifier.size(44.dp)) {
                    Icon(if(call.speakerForced) Icons.Rounded.VolumeUp
                        else Icons.Rounded.VolumeOff,
                        contentDescription = if(call.speakerForced) "자동 출력" else "스피커 켜기",
                        tint = accent)
                }
                Surface(onClick=onHangup, shape=CircleShape,
                    color=Color(0xFFC3484B),modifier=Modifier.size(44.dp)) {
                    Box(contentAlignment=Alignment.Center) {
                        Icon(Icons.Rounded.CallEnd,contentDescription="통화 종료",
                            tint=Color.White)
                    }
                }
            }
            if (fraction < .94f) {
                Box(Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment=Alignment.Center) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally,
                        verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        AssistantAvatarView(profile, if(fraction >= .35f) 66.dp else 110.dp)
                        Text(stateText, color=Color(0xFFF0F5F7), fontSize=17.sp,
                            fontWeight=FontWeight.Medium, textAlign=TextAlign.Center)
                        if (fraction < .3f)
                            Text("편하게 말씀하세요",color=secondary,fontSize=13.sp)
                    }
                }
            }
        }

        // This overlay owns the real viewport height, no nested half-height panels.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetHeight)
                .background(Color(0xFF0F151A),
                    RoundedCornerShape(topStart=18.dp,topEnd=18.dp))
        ) {
            val grip = Modifier.fillMaxWidth().height(handleHeight)
                .pointerInput(dragRange) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            fraction = (fraction - delta / dragRange).coerceIn(0f,1f)
                        },
                        onDragEnd = {
                            fraction = listOf(0f,.5f,1f)
                                .minByOrNull { abs(it-fraction) } ?: fraction
                        }
                    )
                }
                .clickable(onClick=toggle)
            Box(grip,contentAlignment=Alignment.Center) {
                Box(Modifier.size(40.dp,4.dp)
                    .background(Color(0xFF768C99),RoundedCornerShape(50)))
            }
            if(fraction > .015f) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    chat()
                }
            }
        }
    }
}
