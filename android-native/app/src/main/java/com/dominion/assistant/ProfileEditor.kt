package com.dominion.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dominion.assistant.data.AssistantProfile

private val avatars = listOf(
    Triple("star", "✦", "별빛"),
    Triple("moon", "☾", "달"),
    Triple("sun", "☀", "햇살"),
    Triple("leaf", "❋", "숲"),
    Triple("heart", "♡", "하트"),
    Triple("orbit", "◎", "궤도")
)

fun avatarGlyph(id: String): String = avatars.firstOrNull { it.first == id }?.second ?: "✦"

@Composable
fun ProfileEditor(vm: MainViewModel) {
    val accountId = vm.account?.id
    var name by rememberSaveable(accountId) { mutableStateOf(vm.profile.name) }
    var style by rememberSaveable(accountId) { mutableStateOf(vm.profile.style) }
    var language by rememberSaveable(accountId) { mutableStateOf(vm.profile.language) }
    var notes by rememberSaveable(accountId) {
        mutableStateOf(vm.profile.notes.joinToString("\n"))
    }
    var avatar by rememberSaveable(accountId) { mutableStateOf(vm.profile.avatar) }
    var saved by remember(accountId) { mutableStateOf(false) }
    var pcConfirm by remember { mutableStateOf(false) }
    val selectedPc = vm.pcs.firstOrNull { it.id == vm.selectedPcId && it.online }
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF23392B))) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("나의 AI 비서",fontWeight=FontWeight.Bold,fontSize=19.sp)
            Text("비서 이름, 말투, 언어, 기억 메모는 이 기기에 계정별로 보관합니다.",
                color=Color(0xFFB8D2C0),fontSize=12.sp)
            Text("비서 아이콘",fontSize=13.sp)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(avatars) { option ->
                    FilterChip(
                        selected=avatar==option.first,
                        onClick={avatar=option.first; saved=false},
                        label={Text(option.second+" "+option.third)}
                    )
                }
            }
            OutlinedTextField(value=name,onValueChange={name=it.take(40);saved=false},
                label={Text("비서 이름")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(value=style,onValueChange={style=it.take(800);saved=false},
                label={Text("성격·말투")},modifier=Modifier.fillMaxWidth(),
                minLines=2,maxLines=4)
            Text("대화 언어",fontSize=13.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=language=="ko",onClick={language="ko";saved=false},
                    label={Text("한국어")})
                FilterChip(selected=language=="en",onClick={language="en";saved=false},
                    label={Text("English")})
            }
            OutlinedTextField(value=notes,onValueChange={notes=it.take(6000);saved=false},
                label={Text("기억 메모 · 한 줄에 하나씩, 최대 20개")},
                minLines=3,maxLines=8,modifier=Modifier.fillMaxWidth())
            Button(enabled=!vm.profileBusy,onClick={
                vm.saveProfile(AssistantProfile(
                    name=name,style=style,language=language,
                    notes=notes.lines().map{it.trim()}.filter{it.isNotEmpty()},
                    avatar=avatar
                )){saved=true}
            },modifier=Modifier.fillMaxWidth()){
                Text(if(vm.profileBusy)"저장 중…" else "비서 설정 저장")
            }
            if(saved)Text("이 기기에 저장했습니다.",color=Color(0xFFB5E4BE),fontSize=12.sp)
            if(selectedPc != null) {
                HorizontalDivider(color=Color(0xFF435B4C))
                Text("PC의 비서 설정은 별도로 저장됩니다. 현재 저장된 설정을 선택한 PC에도 반영할 수 있습니다.",
                    color=Color(0xFFB8D2C0),fontSize=12.sp)
                OutlinedButton(onClick={pcConfirm=true},enabled=!vm.profileBusy) {
                    Text("PC에 비서 설정 적용 · "+selectedPc.name)
                }
            }
        }
    }
    if(pcConfirm && selectedPc != null)AlertDialog(
        onDismissRequest={pcConfirm=false},
        title={Text("PC 비서 설정 변경")},
        text={Text("선택한 PC: "+selectedPc.name+
            "\n이 기기에 저장된 이름·말투·언어·기억 메모로 PC 설정을 덮어씁니다.")},
        confirmButton={TextButton(onClick={
            pcConfirm=false
            vm.syncProfileToPc(selectedPc)
        }){Text("PC에도 적용")}},
        dismissButton={TextButton(onClick={pcConfirm=false}){Text("취소")}}
    )
}
