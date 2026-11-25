package com.dominion.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dominion.assistant.data.AssistantProfile
import com.dominion.assistant.voice.CallStatus
import kotlinx.coroutines.delay

/**
 * Mirrors mobile/VoiceCallScreen.tsx:
 * the SAME chat composer is revealed by dragging the handle up, at 0/50/100%.
 * The LiveKit foreground service owns the call; expanding chat never disconnects.
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
    var fraction by remember { mutableFloatStateOf(0f) }
    var elapsed by remember(call.startedAt) { mutableIntStateOf(0) }
    LaunchedEffect(call.startedAt) {
        if (call.startedAt > 0) while (true) {
            elapsed = ((System.currentTimeMillis() - call.startedAt) / 1000).toInt()
                .coerceAtLeast(0)
            delay(1000)
        }
    }
    val color = Color(0xFFA5D5A1)
    val muted = Color(0xFFB8D2C0)
    val talking = call.phase == "connected" && call.botJoined
    val stateText = when(call.phase) {
        "connecting" -> "연결하고 있어요"
        "reconnecting" -> "다시 연결 중이에요"
        "connected" -> if (talking) "듣고 있어요" else "비서가 들어오는 중이에요"
        else -> call.message.ifBlank { "연결 중이에요" }
    }
    val minutes = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    val cycle: () -> Unit = {
        fraction = when {
            fraction < .25f -> .5f
            fraction < .75f -> 1f
            else -> 0f
        }
    }
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF14261D), Color(0xFF14261D)))
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp,
            bottom = 8.dp),verticalAlignment = Alignment.CenterVertically) {
            AssistantAvatarView(profile,42.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(profile.name, color = Color(0xFFEFF5F0), fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).background(color,CircleShape))
                    Text("$minutes · $stateText", color = muted, fontSize = 12.sp)
                }
            }
            Text("통화 중",color=color,fontSize=12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            VoiceControl(
                label=if(call.muted)"마이크 켜기" else "음소거",
                symbol=if(call.muted)"○" else "◉",
                selected=call.muted, modifier=Modifier.weight(1f),action=onMute
            )
            VoiceControl(
                label=if(call.speakerForced)"자동 출력" else "스피커",
                symbol="♫", selected=call.speakerForced,
                modifier=Modifier.weight(1f),action=onSpeaker
            )
            VoiceControl(
                label="통화 종료",symbol="×",danger=true,
                modifier=Modifier.weight(1f),action=onHangup
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val handleHeight = 48.dp
            val contentHeight = (maxHeight - handleHeight).coerceAtLeast(0.dp)
            val density = LocalDensity.current
            val heightPx = with(density) { contentHeight.toPx() }.coerceAtLeast(1f)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(contentHeight * (1f-fraction)),
                    contentAlignment=Alignment.Center) {
                    if(fraction < .94f) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally,
                            verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            AssistantAvatarView(profile, if(fraction>.28f)76.dp else 112.dp)
                            Text(stateText,color=Color(0xFFEFF5F0),fontSize=18.sp,
                                fontWeight=FontWeight.Medium,textAlign=TextAlign.Center)
                            Text("편하게 말씀하세요",color=muted,fontSize=13.sp)
                        }
                    }
                }
                val swipeModifier=Modifier
                    .fillMaxWidth()
                    .height(handleHeight)
                    .background(Color(0xFF23392B))
                    .pointerInput(heightPx) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                fraction=(fraction-dragAmount/heightPx).coerceIn(0f,1f)
                            },
                            onDragEnd = {
                                fraction = listOf(0f,.5f,1f).minByOrNull {
                                    kotlin.math.abs(it-fraction)
                                } ?: 0f
                            }
                        )
                    }
                    .clickable(onClick=cycle)
                Column(swipeModifier,horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.Center) {
                    Box(Modifier.size(width=42.dp,height=4.dp)
                        .background(Color(0xFFB8D2C0),RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if(fraction < .05f)"위로 밀어 대화 열기"
                        else "위아래로 밀어 대화 조절",
                        color=muted,fontSize=11.sp
                    )
                }
                if(fraction > .01f) {
                    Surface(
                        modifier=Modifier.fillMaxWidth().height(contentHeight*fraction),
                        color=Color(0xFF111815),
                        shape=RoundedCornerShape(topStart=18.dp,topEnd=18.dp)
                    ) {
                        chat()
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceControl(
    label: String,symbol:String,modifier:Modifier=Modifier,
    danger:Boolean=false,selected:Boolean=false,action:()->Unit
) {
    Surface(onClick=action,modifier=modifier.height(62.dp),
        shape=RoundedCornerShape(18.dp),
        color=when {
            danger -> Color(0xFF6A3535)
            selected -> Color(0xFF304D3A)
            else -> Color(0xFF304D3A)
        }
    ) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center) {
            Text(symbol,color=if(danger)Color(0xFFFFC4BA) else Color(0xFFF4F7F8),
                fontSize=22.sp)
            Text(label,color=if(danger)Color(0xFFFFD1C9) else Color(0xFFEFF5F0),
                fontSize=11.sp,maxLines=1)
        }
    }
}
