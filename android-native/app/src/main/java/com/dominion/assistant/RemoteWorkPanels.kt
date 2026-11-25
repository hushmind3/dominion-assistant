package com.dominion.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dominion.assistant.data.WorkInputPolicy
import com.dominion.assistant.data.WorkSection

/** Compose is compiled on-device; only labels and pre-approved action codes are remote data. */
@Composable
fun RemoteWorkPanels(vm:MainViewModel,sections:List<WorkSection>,open:(String)->Unit){
 sections.forEach{section->
  key(section.id){
   Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF23392B))){
    Column(Modifier.fillMaxWidth().padding(16.dp),
     verticalArrangement=Arrangement.spacedBy(12.dp)){
     Text(section.title,fontSize=17.sp,fontWeight=FontWeight.Bold)
     Text(section.description,color=Color(0xFFB8D2C0),fontSize=12.sp)
     when(section){
      is WorkSection.Actions->{
       TextButton(onClick={vm.connectGoogle(open)}){Text("Google 서비스 연결")}
       section.operations.forEach{operation->
        OutlinedButton(onClick={vm.startGoogle(operation.operation)},
         modifier=Modifier.fillMaxWidth()){Text(operation.label)}
       }
      }
      is WorkSection.Form->{
       var url by rememberSaveable(section.id){mutableStateOf("")}
       var goal by rememberSaveable(section.id){mutableStateOf("")}
       var awaiting by remember{mutableStateOf(false)}
       OutlinedTextField(value=url,onValueChange={url=it.take(2048)},
        label={Text(section.urlLabel)},singleLine=true,modifier=Modifier.fillMaxWidth())
       OutlinedTextField(value=goal,onValueChange={goal=it.take(4000)},
        label={Text(section.goalLabel)},minLines=2,maxLines=5,
        modifier=Modifier.fillMaxWidth())
       val valid=WorkInputPolicy.officialHttps(url)!=null&&goal.trim().isNotEmpty()
       Button(onClick={awaiting=true},enabled=valid,
        modifier=Modifier.fillMaxWidth()){Text(section.submitLabel)}
       if(awaiting)AlertDialog(onDismissRequest={awaiting=false},
        title={Text("웹 업무 확인")},
        text={Text(url.take(150)+"\n\n"+goal.take(1500)+
          "\n\n사이트에서 실제 동작을 시작할 수 있습니다. 변경 단계에는 별도 승인이 필요합니다.")},
        confirmButton={TextButton(onClick={
         awaiting=false
         vm.startBrowser(url,goal)
         goal=""
        }){Text("웹 업무 시작")}},
        dismissButton={TextButton(onClick={awaiting=false}){Text("취소")}})
      }
     }
    }
   }
  }
 }
}
