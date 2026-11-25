package com.dominion.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.dominion.assistant.data.ChatLine
import com.dominion.assistant.data.AssistantProfile
import com.dominion.assistant.data.WorkItem
import com.dominion.assistant.voice.NativeVoice
import com.dominion.assistant.voice.VoiceCallService
import com.dominion.assistant.voice.VoiceCallState
import com.dominion.assistant.data.RemotePc
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay

private val Forest = Color(0xFF0F151A)
private val Panel = Color(0xFF202A30)
private val Accent = Color(0xFF9EE9D0)
private val Secondary = Color(0xFF9DADB4)

class MainActivity : ComponentActivity() {
    var openVoiceEvent by mutableIntStateOf(0)
        private set
    var openWorkEvent by mutableIntStateOf(0)
        private set

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("dominion_open_voice",false)) openVoiceEvent++
        if (intent.getBooleanExtra("dominion_open_work",false)) openWorkEvent++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent?.getBooleanExtra("dominion_open_voice",false)==true) openVoiceEvent++
        if (intent?.getBooleanExtra("dominion_open_work",false)==true) openWorkEvent++
        super.onCreate(savedInstanceState)
        val state = ViewModelProvider(this)[MainViewModel::class.java]
        setContent {
            val voice = remember { NativeVoice(applicationContext) }
            DisposableEffect(voice) { onDispose { voice.close() } }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, onPrimary = Forest,
                background = Forest, onBackground = Color.White,
                surface = Panel, onSurface = Color.White,
                secondary = Secondary, error = Color(0xFFFFB4A8)
            )) {
                DominionScreen(state, voice, this)
            }
        }
    }
}

@Composable
private fun DominionScreen(vm: MainViewModel, voice: NativeVoice, activity: MainActivity) {
    // The conversation owns the home screen. Other functions live in a drawer.
    var page by rememberSaveable { mutableIntStateOf(0) }
    // A single draft survives opening and closing the in-call chat sheet.
    var chatDraft by rememberSaveable(vm.account?.id) { mutableStateOf("") }
    var showSignOut by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val call by VoiceCallState.state.collectAsState()
    val inCall = call.phase in setOf("connecting","connected","reconnecting")
    LaunchedEffect(activity.openVoiceEvent) {
        if(activity.openVoiceEvent>0) page=if(inCall)0 else 2
    }
    LaunchedEffect(activity.openWorkEvent) {
        if(activity.openWorkEvent>0&&!inCall)page=1
    }
    LaunchedEffect(inCall) {
        if(inCall) {
            page=0
            drawer.close()
        }
    }
    ModalNavigationDrawer(
        drawerState=drawer,
        gesturesEnabled=vm.account!=null&&!inCall,
        drawerContent={
            if(vm.account!=null) {
                ModalDrawerSheet(
                    drawerContainerColor=Color(0xFF172127),
                    modifier=Modifier.width(292.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(start=22.dp,end=18.dp,
                        top=28.dp,bottom=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        AssistantAvatarView(vm.profile,52.dp)
                        Text(vm.profile.name, fontSize=21.sp,
                            fontWeight=FontWeight.SemiBold,color=Color(0xFFF2F7F2))
                        Text("내 곁의 비서",color=Secondary,fontSize=12.sp)
                    }
                    HorizontalDivider(color=Color(0xFF304047))
                    listOf(
                        Triple(0,"대화","◌"),
                        Triple(2,"통화","☎"),
                        Triple(3,"설정","⚙")
                    ).forEach { (index,label,glyph) ->
                        NavigationDrawerItem(
                            label={Text(label,fontSize=15.sp)},
                            selected=page==index,
                            icon={Text(glyph,fontSize=20.sp)},
                            onClick={
                                page=index
                                scope.launch{drawer.close()}
                            },
                            modifier=Modifier.padding(horizontal=12.dp,vertical=3.dp),
                            colors=NavigationDrawerItemDefaults.colors(
                                selectedContainerColor=Color(0xFF28463E),
                                selectedTextColor=Color(0xFFD0F2E7),
                                unselectedTextColor=Color(0xFFDDE5E9)
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("DOMINION · Android",modifier=Modifier.padding(22.dp),
                        color=Color(0xFF74897B),fontSize=11.sp)
                }
            }
        }
    ) {
        Scaffold(
            containerColor=Forest,
            topBar={
                if(!inCall) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding()
                            .padding(horizontal=14.dp,vertical=9.dp),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        if(vm.account!=null) {
                            IconButton(onClick={scope.launch{drawer.open()}}) {
                                Text("☰",fontSize=22.sp,color=Color(0xFFD9E6DC))
                            }
                        }
                        AssistantAvatarView(vm.profile,36.dp)
                        Column(Modifier.weight(1f)) {
                            Text("도미니온",fontWeight=FontWeight.SemiBold,
                                fontSize=18.sp,color=Color(0xFFF2F7F2))
                            Text(if(vm.account==null)"나의 AI 비서" else "언제든 말 걸어주세요",
                                color=Secondary,fontSize=11.sp)
                        }
                        if(vm.account!=null) {
                            IconButton(onClick={page=2}) {
                                Text("☎",fontSize=23.sp,color=Accent)
                            }
                        }
                        if(vm.busy)CircularProgressIndicator(
                            Modifier.size(18.dp),strokeWidth=2.dp,color=Accent)
                    }
                    HorizontalDivider(color=Color(0xFF2D3940),thickness=.5.dp)
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                if(vm.error.isNotBlank()) {
                    Row(Modifier.fillMaxWidth()
                        .background(Color(0xFF51342F)).padding(horizontal=14.dp,vertical=8.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Text(vm.error,Modifier.weight(1f),fontSize=12.sp,
                            color=Color(0xFFFFD6D0))
                        TextButton(onClick=vm::clearError){Text("닫기")}
                    }
                }
                if(vm.account==null) {
                    LoginScreen(vm){url->
                        activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
                    }
                } else if(inCall) {
                    VoiceConversationSheet(
                        profile=vm.profile,call=call,
                        onMute={VoiceCallService.command(activity,VoiceCallService.MUTE)},
                        onSpeaker={VoiceCallService.command(activity,VoiceCallService.SPEAKER)},
                        onHangup={VoiceCallService.command(activity,VoiceCallService.STOP)}
                    ) {
                        ChatScreen(vm,voice,onNavigate={page=it},draft=chatDraft,
                            onDraftChange={chatDraft=it},inVoiceSheet=true)
                    }
                } else {
                    when(page) {
                        0 -> ChatScreen(vm,voice,onNavigate={page=it},draft=chatDraft,
                             onDraftChange={chatDraft=it})
                        1 -> WorkScreen(vm,open={url->
                            activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
                        })
                        2 -> CallScreen(vm,activity,voice)
                        4 -> PcChatScreen(vm)
                        else -> SettingsScreen(vm,
                            onAssistantSettings={
                                runCatching{
                                    activity.startActivity(Intent(
                                        Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                                }
                            },
                            onSignOut={showSignOut=true}
                        )
                    }
                }
            }
        }
    }
    if(showSignOut)AlertDialog(
        onDismissRequest={showSignOut=false},
        title={Text("계정 연결을 해제할까요?")},
        text={Text("이 기기의 Cloud 연결만 해제합니다. 대화는 기기에 남습니다.")},
        confirmButton={TextButton(onClick={
            showSignOut=false
            vm.signOut()
        }){Text("연결 해제")}},
        dismissButton={TextButton(onClick={showSignOut=false}){Text("취소")}}
    )
}

@Composable
private fun LoginScreen(vm: MainViewModel, open: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(26.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(110.dp).background(Panel, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("✦", color = Accent, fontSize = 72.sp) }
        Spacer(Modifier.height(24.dp))
        Text("나의 DOMINION", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("대화하고, 업무를 맡기고, 음성으로 이야기하세요.",
            color = Secondary, fontSize = 15.sp)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { vm.signIn(open) },
            enabled = !vm.signingIn, modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Text(if (vm.signingIn) "브라우저 승인 대기 중…" else "Google 계정으로 연결")
        }
        if (vm.signingIn) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = vm::cancelPairing) { Text("로그인 취소") }
        }
        Spacer(Modifier.height(16.dp))
        Text("기존 DOMINION Cloud 계정을 사용합니다.\n별도 AI 공급자 키는 휴대폰에 저장하지 않습니다.",
            color = Secondary, fontSize = 12.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun ChatScreen(
    vm: MainViewModel,
    voice: NativeVoice,
    onNavigate: (Int) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    inVoiceSheet: Boolean = false
) {
    val context = LocalContext.current
    val active by VoiceCallState.state.collectAsState()
    val calling = active.phase in setOf("connecting","connected","reconnecting")
    var showActions by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if(vm.messages.isNotEmpty()) list.animateScrollToItem(vm.messages.lastIndex)
    }
    val microphone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if(granted && !calling) voice.listen { heard ->
            vm.send(heard) { response ->
                if(VoiceCallState.state.value.phase=="idle") voice.speak(response)
            }
        }
    }
    val dictate: () -> Unit = {
        if(voice.listening) voice.stopListening()
        else if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)
            ==PackageManager.PERMISSION_GRANTED) {
            voice.listen { heard ->
                vm.send(heard) { response ->
                    if(VoiceCallState.state.value.phase=="idle") voice.speak(response)
                }
            }
        } else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
    Column(Modifier.fillMaxSize().background(Forest)) {

        if(showModels && !inVoiceSheet) {
            Row(Modifier.fillMaxWidth().padding(start=18.dp,end=10.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Text("모델 선택",Modifier.weight(1f),color=Secondary,fontSize=12.sp)
                TextButton(onClick={showModels=false}) { Text("닫기",fontSize=12.sp) }
            }
            if(vm.models.isEmpty()) {
                Row(Modifier.padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("모델 목록을 불러오지 못했습니다.",fontSize=12.sp,color=Secondary)
                    TextButton(onClick=vm::refreshModels){Text("다시 불러오기")}
                }
            } else LazyRow(
                contentPadding=PaddingValues(horizontal=16.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                items(vm.models,key={it}) { model ->
                    FilterChip(
                        selected=vm.selectedModel==model,
                        onClick={vm.chooseModel(model);showModels=false},
                        label={Text(model.substringAfterLast('/').take(24),fontSize=12.sp)}
                    )
                }
            }
        }
        LazyColumn(
            state=list,
            modifier=Modifier.weight(1f).fillMaxWidth(),
            contentPadding=PaddingValues(horizontal=16.dp,vertical=16.dp),
            verticalArrangement=Arrangement.spacedBy(15.dp)
        ) {
            if(vm.messages.isEmpty()) item {
                Column(
                    Modifier.fillMaxWidth().padding(top=if(inVoiceSheet)15.dp else 64.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(11.dp)
                ) {
                    AssistantAvatarView(vm.profile,if(inVoiceSheet)48.dp else 74.dp)
                    Spacer(Modifier.height(5.dp))
                    Text("무엇을 도와드릴까요?",
                        color=Color(0xFFF2F5F0),
                        fontSize=if(inVoiceSheet)19.sp else 24.sp,
                        fontWeight=FontWeight.SemiBold)
                    Text("이야기부터 심부름까지, 편하게 말씀하세요.",
                        color=Secondary,fontSize=13.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            items(vm.messages) { message -> MessageBubble(message,vm.profile) }
        }
        if(voice.status!="음성 대기"&&!calling) {
            Text(voice.status,color=Secondary,fontSize=11.sp,
                modifier=Modifier.padding(horizontal=18.dp,vertical=4.dp))
        }
        if(vm.busy) {
            Text("답변을 준비하고 있어요…",color=Secondary,fontSize=11.sp,
                modifier=Modifier.padding(horizontal=18.dp,vertical=4.dp))
        }
        Surface(
            color=Panel,shape=RoundedCornerShape(28.dp),tonalElevation=1.dp,
            modifier=Modifier.fillMaxWidth().imePadding()
                .padding(horizontal=12.dp,vertical=10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=4.dp),
                verticalAlignment=Alignment.Bottom,
                horizontalArrangement=Arrangement.spacedBy(2.dp)
            ) {
                if(!calling) {
                    IconButton(onClick={showActions=true},modifier=Modifier.size(44.dp)) {
                        Text("+",color=Accent,fontSize=29.sp,fontWeight=FontWeight.Light)
                    }
                }
                BasicTextField(
                    value=draft,
                    onValueChange={onDraftChange(it.take(8000))},
                    modifier=Modifier.weight(1f).padding(vertical=11.dp,horizontal=8.dp),
                    textStyle=TextStyle(color=Color(0xFFF4F7F9),fontSize=15.sp),
                    cursorBrush=androidx.compose.ui.graphics.SolidColor(Accent),
                    minLines=1,maxLines=4,
                    decorationBox={input->
                        Box {
                            if(draft.isEmpty())
                                Text(if(calling)"통화 중 메시지 입력" else "무엇이든 말씀하세요",
                                    color=Color(0xFF93A3AC),fontSize=14.sp)
                            input()
                        }
                    }
                )
                if(draft.isBlank()&&!calling) {
                    IconButton(onClick=dictate,modifier=Modifier.size(44.dp)) {
                        Text(if(voice.listening)"■" else "🎙",
                            color=if(voice.listening)Color(0xFFE9B5AB) else Secondary,
                            fontSize=18.sp)
                    }
                }
                if(draft.isNotBlank()) {
                    FilledIconButton(
                        enabled=!vm.busy&&vm.selectedModel.isNotBlank(),
                        onClick={
                            val message=draft.trim()
                            onDraftChange("")
                            vm.send(message)
                        },
                        modifier=Modifier.size(42.dp),
                        colors=IconButtonDefaults.filledIconButtonColors(
                            containerColor=Accent,contentColor=Forest
                        )
                    ) {
                        Text("↑",fontWeight=FontWeight.Bold,fontSize=24.sp)
                    }
                }
            }
        }
        if(voice.status=="답변을 읽고 있습니다"&&!calling)
            TextButton(onClick=voice::interrupt,modifier=Modifier.align(Alignment.End)){
                Text("읽기 중지",fontSize=12.sp)
            }
    }
    if(showActions) {
        ModalBottomSheet(
            onDismissRequest={showActions=false},
            containerColor=Color(0xFF1C272D)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp),
                verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("무엇을 해드릴까요?",modifier=Modifier.padding(start=10.dp,bottom=10.dp),
                    fontSize=19.sp,fontWeight=FontWeight.SemiBold)
                listOf(
                    Triple("✦","심부름 맡기기",1),
                    Triple("▤","PC와 대화·파일",4),
                    Triple("◷","일정·알림",1),
                    Triple("☎","통화",2),
                    Triple("◇","모델 바꾸기",-1),
                    Triple("◎","설정",3)
                ).forEach { (glyph,label,destination) ->
                    Surface(onClick={
                        showActions=false
                        if(destination==-1)showModels=true else onNavigate(destination)
                    },shape=RoundedCornerShape(14.dp),
                        color=Color.Transparent,modifier=Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=13.dp),
                            verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            Text(glyph,color=Accent,fontSize=23.sp)
                            Text(label,color=Color(0xFFEAF2F4),fontSize=15.sp)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun CallScreen(vm: MainViewModel, activity: ComponentActivity, voice: NativeVoice) {
    val state by VoiceCallState.state.collectAsState()
    var requestPc by remember { mutableStateOf<RemotePc?>(null) }
    var seconds by remember(state.startedAt) { mutableIntStateOf(0) }
    LaunchedEffect(state.startedAt) {
        if (state.startedAt > 0) while (true) {
            seconds = ((System.currentTimeMillis() - state.startedAt) / 1000).toInt().coerceAtLeast(0)
            delay(1000)
        }
    }
    LaunchedEffect(vm.account?.id) {
        while (vm.account != null) {
            vm.refreshPcs()
            delay(15_000)
        }
    }
    val launchCall: (RemotePc) -> Unit = { pc ->
        voice.stopListening()
        voice.interrupt()
        vm.startVoice(pc) { pairing, selected, id ->
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) false
            else runCatching {
                VoiceCallService.start(activity, pairing, selected.id, id)
                true
            }.getOrDefault(false)
        }
    }
    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val microphoneGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (microphoneGranted) requestPc?.let(launchCall)
        requestPc = null
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI 비서와 통화", fontWeight = FontWeight.Bold, fontSize = 23.sp,
                modifier = Modifier.weight(1f))
            TextButton(onClick = vm::refreshPcs) { Text("PC 새로고침") }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(92.dp).background(Color(0xFF2E5B53), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("✦", color = Accent, fontSize = 62.sp)
                }
                Text(
                    when (state.phase) {
                        "connected" -> if (state.botJoined) "AI와 연결됨" else "AI 입장 대기 중"
                        "connecting" -> "연결 중"
                        "reconnecting" -> "다시 연결 중"
                        "error" -> "연결 오류"
                        else -> "음성 통화 대기"
                    },
                    fontWeight = FontWeight.Bold, fontSize = 20.sp
                )
                if (state.message.isNotBlank()) Text(state.message, color = Secondary)
                if (state.startedAt > 0) Text(
                    "%02d:%02d".format(seconds / 60, seconds % 60),
                    color = Accent, fontSize = 26.sp
                )
                if (state.phase in setOf("connected","connecting","reconnecting")) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            VoiceCallService.command(activity, VoiceCallService.MUTE)
                        },modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal=4.dp,vertical=12.dp)) {
                            Text(if (state.muted) "마이크 켜기" else "음소거",fontSize=12.sp,maxLines=1)
                        }
                        OutlinedButton(onClick = {
                            VoiceCallService.command(activity, VoiceCallService.SPEAKER)
                        },modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal=4.dp,vertical=12.dp)) {
                            Text(if (state.speakerForced) "자동 출력" else "스피커",fontSize=12.sp,maxLines=1)
                        }
                        Button(onClick = {
                            VoiceCallService.command(activity, VoiceCallService.STOP)
                        },modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal=4.dp,vertical=12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB64D40))) {
                            Text("통화 종료", color = Color.White,fontSize=12.sp,maxLines=1)
                        }
                    }
                    Text("화면을 꺼도 통화는 유지됩니다. 알림에서 음소거와 종료를 누를 수 있습니다.",
                        fontSize = 12.sp, color = Secondary)
                }
            }
        }
        if (state.phase !in setOf("connected","connecting","reconnecting")) {
            Text("연결 가능한 내 PC", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("PC에서 음성 비서를 설정하고 온라인으로 연결해야 합니다. 통화에 설정한 음성 API 비용이 발생할 수 있습니다.",
                color = Secondary, fontSize = 12.sp, lineHeight = 19.sp)
            if (vm.pcs.isEmpty()) Text("연결된 PC가 없습니다.", color = Secondary)
            vm.pcs.forEach { pc ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Row(Modifier.fillMaxWidth().padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pc.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(if (pc.online) "온라인" else "오프라인",
                                color = Secondary, fontSize = 12.sp)
                        }
                        Button(
                            enabled = pc.online && !vm.voiceBusy,
                            onClick = {
                                val needed = mutableListOf<String>()
                                if (ContextCompat.checkSelfPermission(activity,
                                        Manifest.permission.RECORD_AUDIO) !=
                                    PackageManager.PERMISSION_GRANTED) {
                                    needed.add(Manifest.permission.RECORD_AUDIO)
                                }
                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(activity,
                                        Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED) {
                                    needed.add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                if (needed.isEmpty()) launchCall(pc)
                                else {
                                    requestPc = pc
                                    permissionRequest.launch(needed.toTypedArray())
                                }
                            }
                        ) { Text("전화 걸기") }
                    }
                }
            }
            if (vm.voiceBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("PC에서 음성 연결을 준비하는 중…", color = Secondary)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatLine, profile: AssistantProfile) {
    val mine = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start,
        verticalAlignment=Alignment.Bottom
    ) {
        if(!mine) {
            AssistantAvatarView(profile,28.dp)
            Spacer(Modifier.width(9.dp))
        }
        Column(horizontalAlignment=if(mine)Alignment.End else Alignment.Start) {
            if(!mine) {
                Text(profile.name,color=Secondary,fontSize=11.sp,
                    modifier=Modifier.padding(start=4.dp,bottom=5.dp))
            }
            Surface(
                modifier=Modifier.widthIn(max=304.dp),
                color=if(mine)Color(0xFF2E5B53) else Panel,
                shape=if(mine)RoundedCornerShape(18.dp,18.dp,5.dp,18.dp)
                      else RoundedCornerShape(18.dp,18.dp,18.dp,5.dp)
            ) {
                Text(message.text,Modifier.padding(horizontal=15.dp,vertical=11.dp),
                    color=Color(0xFFF0F6F5),fontSize=15.sp,lineHeight=22.sp)
            }
        }
    }
}

@Composable
private fun WorkScreen(vm: MainViewModel, open: (String) -> Unit) {
    var uploadTarget by remember { mutableStateOf<WorkItem?>(null) }
    val chooseFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) uploadTarget?.let { vm.uploadWorkFile(it, uri) }
        uploadTarget = null
    }
    var site by rememberSaveable { mutableStateOf("") }
    var goal by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(vm.account?.id) {
        while (vm.account != null) {
            vm.refreshJobs()
            vm.refreshPcs()
            vm.refreshInbox()
            vm.refreshWorkUi()
            delay(15000)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("심부름", Modifier.weight(1f), fontSize = 22.sp,
                fontWeight = FontWeight.Bold)
            TextButton(onClick = vm::refreshJobs, enabled = !vm.loadingJobs) {
                Text(if (vm.loadingJobs) "확인 중" else "새로고침")
            }
        }
        PcWorkPanel(vm)
        ReminderPanel(vm)
        val serverUi=vm.dynamicWorkUi
        if(serverUi!=null){
            if(serverUi.isEmpty()) Text("Cloud에서 사용 가능한 업무 도구가 없습니다.",
                color=Secondary)
            else RemoteWorkPanels(vm,serverUi,open)
        } else {
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Google 조회", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("계정에서 승인한 읽기 전용 작업만 실행합니다.",
                    fontSize = 12.sp, color = Secondary)
                TextButton(onClick = { vm.connectGoogle(open) }) {
                    Text("Google 서비스 연결·권한 승인")
                }
                listOf(
                    "최근 받은 메일" to "gmail_recent",
                    "다가오는 일정" to "calendar_upcoming",
                    "최근 Drive 파일" to "drive_recent"
                ).forEach { (name, operation) ->
                    OutlinedButton(
                        onClick = { vm.startGoogle(operation) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(name) }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("웹사이트에서 부탁하기", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                OutlinedTextField(
                    value = site, onValueChange = { site = it.take(2048) },
                    label = { Text("공식 웹사이트 HTTPS 주소") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = goal, onValueChange = { goal = it.take(4000) },
                    label = { Text("부탁할 내용") },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
                )
                Text("사이트의 제출·변경 동작은 서버가 별도로 승인을 요청합니다.",
                    fontSize = 12.sp, color = Secondary)
                Button(
                    onClick = { vm.startBrowser(site, goal) },
                    enabled = site.startsWith("https://") && goal.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("내용 확인하고 부탁하기") }
            }
        }
        }
        if (vm.jobs.isEmpty()) {
            Text("아직 등록된 업무가 없습니다.", color = Secondary,
                modifier = Modifier.padding(vertical = 20.dp))
        }
        vm.jobs.forEach { item ->
            WorkCard(item, vm::cancelWork,
                onAction = { job, action -> vm.reviewWork(job, action, open) },
                onChooseFile = { job ->
                    uploadTarget = job
                    chooseFile.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                }
            )
        }
    }
}

@Composable
private fun WorkCard(
    job: WorkItem,
    cancel: (WorkItem) -> Unit,
    onAction: (WorkItem, String) -> Unit,
    onChooseFile: (WorkItem) -> Unit
) {
    val terminal = job.status in setOf("completed", "failed", "cancelled", "needs_review")
    var confirmApproval by remember(job.id, job.approvalId) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (job.kind == "google") "📬" else "🌐", fontSize = 19.sp)
                Spacer(Modifier.width(8.dp))
                Text(job.title, Modifier.weight(1f),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(job.status, color = Accent, fontSize = 12.sp)
            }
            if (job.detail.isNotBlank()) Text(job.detail, fontSize = 13.sp,
                color = Secondary, maxLines = 7)
            if (job.kind == "browser") {
                when (job.status) {
                    "awaiting_approval" -> if (job.approvalId != null) {
                        Button(onClick = { confirmApproval = true }) {
                            Text("동작 확인: " + job.approvalLabel.orEmpty().take(45))
                        }
                    }
                    "awaiting_file" -> if (job.approvalId != null) {
                        Button(onClick = { onChooseFile(job) }) { Text("파일 직접 선택") }
                    }
                    "awaiting_human" -> {
                        Button(onClick = { onAction(job, "view") }) { Text("인증 화면 열기") }
                        OutlinedButton(onClick = { onAction(job, "resume") }) {
                            Text("직접 처리 완료 후 다시 확인")
                        }
                    }
                }
            }
            if (!terminal) TextButton(
                onClick = { cancel(job) }, modifier = Modifier.align(Alignment.End)
            ) { Text("업무 취소") }
        }
    }
    if (confirmApproval) AlertDialog(
        onDismissRequest = { confirmApproval = false },
        title = { Text("웹사이트 동작 승인") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(job.approvalLabel ?: "사이트에서 다음 동작을 실행합니다.")
                Text(job.approvalPreview ?: "현재 화면을 확인해 주세요.",
                    fontSize = 12.sp, color = Secondary, maxLines = 14)
                Text("승인한 동작은 실제 사이트에 반영될 수 있습니다.",
                    fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { confirmApproval = false; onAction(job, "approve") }) {
                Text("이 동작 승인")
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmApproval = false }) { Text("취소") }
        }
    )
}

@Composable
private fun SettingsScreen(
    vm: MainViewModel,
    onAssistantSettings: () -> Unit,
    onSignOut: () -> Unit
) {
    var eraseHistory by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val pushPermission=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ){granted-> if(granted)vm.enablePush() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("설정", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("계정 · " + (vm.account?.id?.take(18) ?: ""),
            color = Secondary, fontSize = 13.sp)
        ProfileEditor(vm)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("알림",fontWeight=FontWeight.Bold)
                Text(if(vm.pushConfigured)
                    "업무 결과·인증 요청·PC 메시지 알림을 휴대폰에서 받을 수 있습니다."
                    else "Firebase 프로젝트 설정이 필요합니다. 지금은 앱 안에서 업무 상태를 조회할 수 있습니다.",
                    color=Secondary,fontSize=13.sp)
                if(vm.pushConfigured) Button(
                    onClick={
                        if(vm.pushEnabled)vm.disablePush()
                        else if(android.os.Build.VERSION.SDK_INT>=33&&
                            ContextCompat.checkSelfPermission(context,
                                Manifest.permission.POST_NOTIFICATIONS)!=
                                    PackageManager.PERMISSION_GRANTED)
                            pushPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.enablePush()
                    },
                    enabled=!vm.pushBusy
                ){Text(if(vm.pushBusy)"처리 중…" else if(vm.pushEnabled)"알림 끄기" else "알림 켜기")}
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("안드로이드 기본 비서", fontWeight = FontWeight.Bold)
                Text("기기 설정에서 DOMINION을 비서로 선택할 수 있습니다. " +
                    "음성 호출 세션은 현재 앱 실행 화면을 열어 줍니다.",
                    color = Secondary, fontSize = 13.sp)
                OutlinedButton(onClick = onAssistantSettings) { Text("기본 비서 설정 열기") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("개인정보", fontWeight = FontWeight.Bold)
                Text("Cloud 인증은 Android Keystore로 보관합니다. " +
                    "대화는 계정별로 기기에 저장됩니다.", color = Secondary, fontSize = 13.sp)
                OutlinedButton(onClick = { eraseHistory = true }) { Text("이 기기의 대화 삭제") }
                OutlinedButton(onClick = onSignOut) { Text("Cloud 연결 해제") }
            }
        }
    }
    if (eraseHistory) AlertDialog(
        onDismissRequest = { eraseHistory = false },
        title = { Text("대화 기록을 삭제할까요?") },
        text = { Text("현재 계정의 이 기기 대화만 삭제합니다.") },
        confirmButton = {
            TextButton(onClick = { vm.clearHistory(); eraseHistory = false }) { Text("삭제") }
        },
        dismissButton = { TextButton(onClick = { eraseHistory = false }) { Text("취소") } }
    )
}
