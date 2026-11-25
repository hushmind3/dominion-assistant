package com.dominion.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dominion.assistant.data.PcTurn
import com.dominion.assistant.data.RemotePc
import kotlinx.coroutines.delay

private val panel=Color(0xFF23392B)
private val muted=Color(0xFFB8D2C0)
private val accent=Color(0xFFB5E4BE)
private val darker=Color(0xFF14261D)

@Composable
fun PcWorkPanel(vm:MainViewModel) {
 var instruction by rememberSaveable { mutableStateOf("") }
 var confirmPc by remember { mutableStateOf<RemotePc?>(null) }
 var cancelTurn by remember { mutableStateOf<Pair<RemotePc,PcTurn>?>(null) }
 val current=vm.pcs.firstOrNull { it.id==vm.selectedPcId }
 LaunchedEffect(vm.selectedPcId, current?.online) {
  if(current?.online==true) while(vm.account!=null) {
   vm.refreshPcWork(current)
   delay(12000)
  }
 }
 Card(colors=CardDefaults.cardColors(containerColor=panel)) {
  Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
   Row {
    Text("내 PC에 업무 맡기기",Modifier.weight(1f),fontWeight=FontWeight.Bold,fontSize=18.sp)
    TextButton(onClick=vm::refreshPcs){Text("PC 찾기")}
   }
   Text("선택한 PC가 업무를 실행합니다. 전송 후 실제 접수 기록을 확인하세요.",color=muted,fontSize=12.sp)
   if(vm.pcs.isEmpty()) Text("연결된 PC가 없습니다.",color=muted)
   vm.pcs.forEach { pc ->
    Row(Modifier.fillMaxWidth()) {
     RadioButton(selected=pc.id==vm.selectedPcId,enabled=pc.online,onClick={vm.refreshPcWork(pc)})
     Column {
      Text(pc.name,fontWeight=FontWeight.SemiBold)
      Text(if(pc.online)"온라인" else "오프라인",fontSize=12.sp,color=muted)
     }
    }
   }
   OutlinedTextField(value=instruction,onValueChange={instruction=it.take(4000)},
    label={Text("PC에서 수행할 업무")},minLines=2,maxLines=5,modifier=Modifier.fillMaxWidth())
   Button(onClick={confirmPc=current},enabled=current?.online==true&&instruction.isNotBlank()&&!vm.pcBusy,
    modifier=Modifier.fillMaxWidth()){
    Text(if(vm.pcBusy)"접수 확인 중…" else "PC 업무 검토 및 전송")
   }
   if(current!=null){
    Row {
     Text("PC 업무 기록",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
     TextButton(onClick={vm.refreshPcWork(current)}){Text("다시 확인")}
    }
    if(vm.pcTurns.isEmpty())Text("이 PC의 작업 기록이 없습니다.",color=muted,fontSize=12.sp)
    vm.pcTurns.takeLast(12).asReversed().forEach { turn ->
     Card(colors=CardDefaults.cardColors(containerColor=darker)){
      Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
       Text(turn.text.take(400),fontSize=13.sp)
       Text(turn.status,color=accent,fontSize=12.sp)
       if(turn.answer.isNotBlank())Text(turn.answer.take(1300),fontSize=13.sp,color=muted)
       if(turn.status=="queued")TextButton(onClick={cancelTurn=current to turn},
        enabled=!vm.pcBusy){Text("대기 작업 취소")}
      }
     }
    }
   }
  }
 }
 Card(colors=CardDefaults.cardColors(containerColor=panel)){
  Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Row {
    Text("PC에서 온 메시지",Modifier.weight(1f),fontWeight=FontWeight.Bold)
    TextButton(onClick=vm::refreshInbox){Text("새로고침")}
   }
   if(vm.inbox.isEmpty())Text("도착한 메시지가 없습니다.",color=muted,fontSize=12.sp)
   vm.inbox.takeLast(10).asReversed().forEach { item ->
    Text(item.text.take(1800),fontSize=13.sp,lineHeight=20.sp)
    HorizontalDivider(color=Color(0xFF435B4C))
   }
  }
 }
 val pc=confirmPc
 if(pc!=null)AlertDialog(onDismissRequest={confirmPc=null},title={Text("PC 업무 실행 확인")},
  text={Text("선택한 PC: "+pc.name+"\n\n"+instruction.take(1200)+
   "\n\n업무 전송 후 실제 실행이 시작될 수 있습니다.")},
  confirmButton={TextButton(onClick={
   confirmPc=null
   vm.sendPcWork(pc,instruction)
   instruction=""
  }){Text("전송")}},
  dismissButton={TextButton(onClick={confirmPc=null}){Text("취소")}})
 val pair=cancelTurn
 if(pair!=null)AlertDialog(onDismissRequest={cancelTurn=null},title={Text("대기 작업 취소")},
  text={Text("PC가 이미 시작한 업무는 이 버튼으로 취소할 수 없습니다.")},
  confirmButton={TextButton(onClick={
   cancelTurn=null
   vm.cancelPcWork(pair.first,pair.second.requestId)
  }){Text("취소 요청")}},
  dismissButton={TextButton(onClick={cancelTurn=null}){Text("돌아가기")}})
}
