package com.dominion.assistant

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dominion.assistant.reminder.LocalReminder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ReminderPanel(vm:MainViewModel) {
    val context=LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var selectedAt by rememberSaveable { mutableLongStateOf(0L) }
    var pendingRemove by remember { mutableStateOf<LocalReminder?>(null) }
    var confirmSchedule by remember { mutableStateOf(false) }
    var consentMessage by remember { mutableStateOf("") }
    val dateFormat=remember { SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.KOREA) }
    val permission=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if(allowed)confirmSchedule=true
        else consentMessage="안드로이드 알림 권한을 허용해 주세요."
    }
    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF23392B))) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(11.dp)) {
            Text("휴대폰 예약 알림",style=MaterialTheme.typography.titleMedium)
            Text("Cloud나 Firebase 없이 이 휴대폰에 예약됩니다. 전력 절약 상태에 따라 예약 시각보다 늦게 도착할 수 있습니다.",
                color=Color(0xFFB8D2C0),fontSize=12.sp)
            OutlinedTextField(value=title,
                onValueChange={title=it.take(120)},label={Text("알림 내용")},
                singleLine=false,maxLines=2,modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={
                val calendar=Calendar.getInstance()
                val picker=DatePickerDialog(context,{_,year,month,day->
                    TimePickerDialog(context,{_,hour,minute->
                        val at=Calendar.getInstance().apply {
                            set(year,month,day,hour,minute,0)
                            set(Calendar.MILLISECOND,0)
                        }
                        selectedAt=at.timeInMillis
                    },calendar.get(Calendar.HOUR_OF_DAY),calendar.get(Calendar.MINUTE),true).show()
                },calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH))
                picker.datePicker.minDate=System.currentTimeMillis()-86_400_000
                picker.show()
            },modifier=Modifier.fillMaxWidth()){
                Text(if(selectedAt==0L)"날짜·시간 선택"
                    else dateFormat.format(Date(selectedAt)))
            }
            Button(onClick={
                if(Build.VERSION.SDK_INT>=33 &&
                    ContextCompat.checkSelfPermission(context,
                        Manifest.permission.POST_NOTIFICATIONS)!=
                    PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else confirmSchedule=true
            },enabled=title.isNotBlank()&&selectedAt>System.currentTimeMillis()+5000L&&
                !vm.reminderBusy,modifier=Modifier.fillMaxWidth()) {
                Text(if(vm.reminderBusy)"예약 처리 중…" else "알림 예약")
            }
            if(consentMessage.isNotBlank())Text(consentMessage,
                color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            HorizontalDivider(color=Color(0xFF42584A))
            Text("예약된 알림",style=MaterialTheme.typography.titleSmall)
            if(vm.reminders.isEmpty())Text("등록된 알림이 없습니다.",
                color=Color(0xFFB8D2C0),fontSize=12.sp)
            vm.reminders.forEach { reminder ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(reminder.title,fontSize=13.sp)
                        Text(dateFormat.format(Date(reminder.time)),
                            color=Color(0xFFB8D2C0),fontSize=12.sp)
                    }
                    TextButton(onClick={pendingRemove=reminder},enabled=!vm.reminderBusy) {
                        Text("취소")
                    }
                }
            }
        }
    }
    if(confirmSchedule)AlertDialog(onDismissRequest={confirmSchedule=false},
        title={Text("알림 예약 확인")},
        text={Text(dateFormat.format(Date(selectedAt))+"\n\n"+title+
            "\n\n앱이 닫혀 있어도 Android가 예약 처리를 시도합니다.")},
        confirmButton={TextButton(onClick={
            confirmSchedule=false
            vm.scheduleReminder(title,selectedAt){
                title=""
                selectedAt=0
            }
        }){Text("예약")}},
        dismissButton={TextButton(onClick={confirmSchedule=false}){Text("돌아가기")}})
    val item=pendingRemove
    if(item!=null)AlertDialog(onDismissRequest={pendingRemove=null},
        title={Text("예약 알림 취소")},
        text={Text(dateFormat.format(Date(item.time))+" · "+item.title)},
        confirmButton={TextButton(onClick={
            pendingRemove=null
            vm.cancelReminder(item.id)
        }){Text("예약 취소")}},
        dismissButton={TextButton(onClick={pendingRemove=null}){Text("돌아가기")}})
}
