package com.dominion.assistant

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dominion.assistant.data.PcTurn
import kotlinx.coroutines.delay
import java.io.File

private val pcInk=Color(0xFFB8D2C0)
private val pcPanel=Color(0xFF23392B)
private val pcAccent=Color(0xFFB5E4BE)

@Composable
fun PcChatScreen(vm:MainViewModel) {
    val context=LocalContext.current
    val pc=vm.pcs.firstOrNull { it.id==vm.selectedPcId }
    var draft by rememberSaveable { mutableStateOf("") }
    var attachmentUri by rememberSaveable { mutableStateOf("") }
    var attachmentName by rememberSaveable { mutableStateOf("") }
    var pendingCameraUri by rememberSaveable { mutableStateOf("") }
    var confirmSend by remember { mutableStateOf(false) }
    val list=rememberLazyListState()

    val chooseFile=rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if(uri!=null){
            attachmentUri=uri.toString()
            attachmentName=runCatching{
                context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,null,null)?.use {c->
                    if(c.moveToFirst())c.getString(0) else null
                }
            }.getOrNull() ?: "첨부파일"
        }
    }
    val capture=rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        if(saved && pendingCameraUri.isNotBlank()){
            attachmentUri=pendingCameraUri
            attachmentName="카메라 사진.jpg"
        }
        pendingCameraUri=""
    }
    LaunchedEffect(vm.account?.id) {
        while(vm.account!=null) {
            vm.refreshPcs()
            delay(15000)
        }
    }
    LaunchedEffect(pc?.id,pc?.online) {
        if(pc?.online==true){
            vm.refreshPcWork(pc)
            while(vm.account!=null) {
                delay(12000)
                if(vm.selectedPcId==pc.id)vm.refreshPcWork(pc)
            }
        }
    }
    LaunchedEffect(vm.pcTurns.size,vm.selectedPcId) {
        if(vm.pcTurns.isNotEmpty())list.animateScrollToItem(vm.pcTurns.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Text("PC에서 처리하는 업무와 대화",
            Modifier.padding(horizontal=16.dp,vertical=8.dp),
            fontWeight=FontWeight.Bold,fontSize=16.sp)
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Text("PC 선택",Modifier.weight(1f),color=pcInk,fontSize=12.sp)
            TextButton(onClick=vm::refreshPcs){Text("다시 찾기")}
        }
        if(vm.pcs.isEmpty()) {
            Text("온라인 PC가 없습니다. PC DOMINION 실행 상태를 확인해 주세요.",
                Modifier.padding(16.dp),color=pcInk)
        } else LazyRow(Modifier.padding(horizontal=12.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(vm.pcs,key={it.id}) { item ->
                FilterChip(selected=item.id==vm.selectedPcId,
                    enabled=item.online,
                    onClick={vm.refreshPcWork(item)},
                    label={Text(item.name.take(20)+if(item.online)" · 연결" else " · 오프라인")})
            }
        }
        if(pc!=null)Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Text("PC 기록을 읽어옵니다. 일반 AI 채팅과 별도로 저장됩니다.",
                Modifier.weight(1f),color=pcInk,fontSize=11.sp)
            TextButton(onClick={vm.refreshPcWork(pc)},enabled=pc.online){
                Text("기록 확인")
            }
        }
        LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth(),
            contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(vm.pcTurns.isEmpty())item {
                Text("PC 업무를 맡기면 이곳에 요청과 실제 결과가 표시됩니다.",
                    Modifier.padding(top=26.dp),color=pcInk)
            }
            items(vm.pcTurns.takeLast(100),key={it.requestId}) { turn ->
                PcTurnCard(turn)
            }
        }
        if(pc!=null && pc.online) {
            if(attachmentUri.isNotBlank()){
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    Text("📎 "+attachmentName.take(45),
                        Modifier.weight(1f),color=pcAccent,fontSize=12.sp)
                    TextButton(onClick={
                        attachmentUri=""
                        attachmentName=""
                    }){Text("삭제")}
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=10.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={
                    chooseFile.launch(arrayOf(
                        "image/*","application/pdf","text/*","application/json",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    ))
                },modifier=Modifier.weight(1f)) {Text("📎 파일")}
                OutlinedButton(onClick={
                    val directory=File(context.cacheDir,"dominion-camera").apply{mkdirs()}
                    val file=File.createTempFile("capture-", ".jpg",directory)
                    val uri=FileProvider.getUriForFile(
                        context,context.packageName+".fileprovider",file)
                    pendingCameraUri=uri.toString()
                    capture.launch(uri)
                },modifier=Modifier.weight(1f)) {Text("📷 카메라")}
            }
            Text(if(attachmentUri.isBlank())"파일·사진을 첨부할 수 있습니다."
                else "사진은 자동 압축, 문서는 512KB 이하 지원",
                Modifier.padding(horizontal=14.dp),color=pcInk,fontSize=11.sp)
            Row(Modifier.fillMaxWidth().imePadding().padding(10.dp),
                verticalAlignment=Alignment.Bottom,
                horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value=draft,onValueChange={draft=it.take(4000)},
                    modifier=Modifier.weight(1f),
                    label={Text("PC에서 할 일")},minLines=1,maxLines=4,
                    shape=RoundedCornerShape(20.dp))
                Button(onClick={confirmSend=true},
                    enabled=draft.isNotBlank()&&!vm.pcBusy,
                    contentPadding=PaddingValues(horizontal=12.dp,vertical=13.dp)){
                    Text(if(vm.pcBusy)"확인 중" else "전송")
                }
            }
        }
    }
    if(confirmSend && pc!=null)AlertDialog(
        onDismissRequest={confirmSend=false},
        title={Text("PC 작업 실행 확인")},
        text={Text("PC: "+pc.name+"\n\n"+draft.take(1100)+
            (if(attachmentUri.isNotBlank())"\n\n첨부: "+attachmentName else "")+
            "\n\n첨부는 PC로 전송되며, 작업이 실제 실행될 수 있습니다.")},
        confirmButton={TextButton(onClick={
            confirmSend=false
            val text=draft
            val file=attachmentUri
            if(file.isNotBlank())vm.sendPcAttachment(pc,Uri.parse(file),text){
                draft=""
                attachmentUri=""
                attachmentName=""
            }else vm.sendPcWork(pc,text){draft=""}
        }) {Text("PC에 맡기기")}},
        dismissButton={TextButton(onClick={confirmSend=false}){Text("취소")}}
    )
}

@Composable
private fun PcTurnCard(turn:PcTurn) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            Surface(color=Color(0xFF355C43),shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth(.92f)) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(turn.text,fontSize=14.sp,lineHeight=21.sp)
                    if(!turn.attachmentName.isNullOrBlank())
                        Text("📎 "+turn.attachmentName,color=pcInk,fontSize=12.sp)
                }
            }
        }
        Surface(color=pcPanel,shape=RoundedCornerShape(18.dp),
            modifier=Modifier.fillMaxWidth(.95f)) {
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                val phase=when(turn.status){
                    "completed" -> "완료"
                    "queued" -> "접수됨"
                    "running" -> "진행 중"
                    "awaiting_approval" -> "PC 승인 필요"
                    "cancelled" -> "취소됨"
                    "failed" -> "실패"
                    else -> turn.status
                }
                Text(phase,color=pcAccent,fontSize=12.sp,fontWeight=FontWeight.Medium)
                val content=turn.answer.ifBlank{
                    turn.error ?: when(turn.status){
                        "queued" -> "PC에서 처리를 준비하고 있습니다."
                        "running" -> "PC가 업무를 수행하고 있습니다."
                        "awaiting_approval" -> "PC 화면에서 승인해 주세요."
                        else -> "PC 기록을 확인해 주세요."
                    }
                }
                Text(content.take(5000),fontSize=14.sp,lineHeight=21.sp)
            }
        }
    }
}
