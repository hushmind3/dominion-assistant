package com.dominion.assistant.voice

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.content.ContextCompat
import com.dominion.assistant.MainActivity
import com.dominion.assistant.R
import com.dominion.assistant.data.AccountVault
import com.dominion.assistant.data.CloudRelay
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class CallStatus(
 val phase:String="idle", val message:String="",val muted:Boolean=false,
 val botJoined:Boolean=false,val startedAt:Long=0L
)
object VoiceCallState {
 private val mutable=MutableStateFlow(CallStatus())
 val state=mutable.asStateFlow()
 internal fun publish(x:CallStatus){mutable.value=x}
}

/** Owns a real native WebRTC call independently of the activity lifecycle. */
class VoiceCallService:Service() {
 companion object{
  const val START="com.dominion.assistant.voice.START"
  const val STOP="com.dominion.assistant.voice.STOP"
  const val MUTE="com.dominion.assistant.voice.MUTE"
  private const val CHANNEL="dominion_voice_calls",ID=4001
  fun start(context:Context,p:VoicePairing,deviceId:String,voiceId:String){
   ContextCompat.startForegroundService(context,Intent(context,VoiceCallService::class.java).setAction(START)
    .putExtra("url",p.url).putExtra("token",p.token).putExtra("room",p.room).putExtra("expiresAt",p.expiresAt)
    .putExtra("deviceId",deviceId).putExtra("voiceId",voiceId))
  }
  fun command(context:Context,action:String){
   require(action==STOP||action==MUTE)
   context.startService(Intent(context,VoiceCallService::class.java).setAction(action))
  }
 }
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private var room:Room?=null
 private var connecting:Job?=null
 private var started=false
 private var stopping=false
 private var activeDevice:String?=null
 private var activeVoiceId:String?=null

 override fun onBind(intent:Intent?):IBinder?=null
 override fun onCreate(){
  super.onCreate()
  getSystemService(NotificationManager::class.java).createNotificationChannel(
   NotificationChannel(CHANNEL,"DOMINION AI 음성 통화",NotificationManager.IMPORTANCE_LOW))
 }
 private fun commandIntent(action:String,code:Int)=PendingIntent.getService(
  this,code,Intent(this,VoiceCallService::class.java).setAction(action),
  PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
 private fun notification(muted:Boolean):Notification{
  val open=PendingIntent.getActivity(this,10,Intent(this,MainActivity::class.java)
   .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_dominion)
   .setContentTitle("DOMINION AI 음성 통화")
   .setContentText(if(muted)"마이크 꺼짐 · 통화 연결 중" else "실시간 음성 대화 중 · 터치해 통화 열기")
   .setOngoing(true).setContentIntent(open)
   .addAction(android.R.drawable.ic_btn_speak_now,if(muted)"마이크 켜기" else "음소거",commandIntent(MUTE,11))
   .addAction(android.R.drawable.ic_menu_close_clear_cancel,"통화 종료",commandIntent(STOP,12))
   .build()
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  when(intent?.action){
   START->{
    if(started||stopping)return START_NOT_STICKY
    if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
     VoiceCallState.publish(CallStatus(phase="error",message="마이크 권한이 필요합니다."))
     stopSelf();return START_NOT_STICKY
    }
    val pairing=try{
     VoicePairing.parse(JSONObject().put("url",intent.getStringExtra("url"))
      .put("token",intent.getStringExtra("token")).put("room",intent.getStringExtra("room"))
      .put("expiresAt",intent.getLongExtra("expiresAt",0)))
    }catch(_:Exception){
     VoiceCallState.publish(CallStatus(phase="error",message="통화 정보가 만료되었습니다."))
     stopSelf();return START_NOT_STICKY
    }
    try{
     if(Build.VERSION.SDK_INT>=29)
      startForeground(ID,notification(false),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
     else startForeground(ID,notification(false))
    }catch(_:Exception){
     VoiceCallState.publish(CallStatus(phase="error",message="통화 서비스를 시작할 수 없습니다."))
     stopSelf();return START_NOT_STICKY
    }
    activeDevice=intent.getStringExtra("deviceId")
    activeVoiceId=intent.getStringExtra("voiceId")
    started=true;stopping=false
    VoiceCallState.publish(CallStatus(phase="connecting",message="음성 서버 연결 중"))
    connecting=scope.launch{connect(pairing)}
   }
   STOP->stopCall()
   MUTE->if(started&&!stopping)scope.launch{
    val active=room?:return@launch
    val newMute=!VoiceCallState.state.value.muted
    try{
     active.localParticipant.setMicrophoneEnabled(!newMute)
     VoiceCallState.publish(VoiceCallState.state.value.copy(muted=newMute))
     getSystemService(NotificationManager::class.java).notify(ID,notification(newMute))
    }catch(_:Exception){
     VoiceCallState.publish(VoiceCallState.state.value.copy(message="음소거 변경 실패"))
    }
   }
  }
  return START_NOT_STICKY
 }
 private suspend fun connect(pairing:VoicePairing){
  try{
   val r=LiveKit.create(applicationContext)
   room=r
   scope.launch{
    r.events.collect{event->
     when(event){
      is RoomEvent.Connected->update("connected","연결됨")
      is RoomEvent.Reconnecting->update("reconnecting","음성 재연결 중")
      is RoomEvent.Reconnected->update("connected","재연결됨")
      is RoomEvent.ParticipantConnected,is RoomEvent.ParticipantDisconnected->syncBot(r)
      is RoomEvent.Disconnected->if(!stopping)stopCall()
      else->{}
     }
    }
   }
   r.connect(pairing.url,pairing.token)
   if(stopping||room!==r)return
   if(r.name!=pairing.room)throw IllegalStateException("음성 방 불일치")
   r.localParticipant.setMicrophoneEnabled(true)
   syncBot(r)
   VoiceCallState.publish(VoiceCallState.state.value.copy(
    phase="connected",message="통화 중",startedAt=System.currentTimeMillis()))
  }catch(e:CancellationException){throw e}
   catch(_:Exception){
    VoiceCallState.publish(CallStatus(phase="error",message="음성 연결 실패"))
    stopCall(preserveError=true)
   }
 }
 private fun syncBot(r:Room){
  val joined=r.remoteParticipants.values.any{it.identity?.value?.startsWith("dominion-bot-")==true}
  VoiceCallState.publish(VoiceCallState.state.value.copy(botJoined=joined))
 }
 private fun update(phase:String,message:String){
  VoiceCallState.publish(VoiceCallState.state.value.copy(phase=phase,message=message))
 }
 private fun stopCall(preserveError:Boolean=false){
  if(stopping)return
  stopping=true
  connecting?.cancel();connecting=null
  room?.release();room=null
  started=false
  val device=activeDevice;val voiceId=activeVoiceId
  activeDevice=null;activeVoiceId=null
  scope.launch {
   if(device!=null&&voiceId!=null){
    // Remote stop is best effort; an uncertain stop is never retried automatically.
    withTimeoutOrNull(8000) {
     try {
      val account=withContext(Dispatchers.IO){AccountVault(this@VoiceCallService).load()}
      if(account!=null)CloudRelay().voiceCommand(account,device,"stop",voiceId)
     }catch(_:Exception){}
    }
   }
   if(!preserveError)VoiceCallState.publish(CallStatus())
   stopForeground(STOP_FOREGROUND_REMOVE)
   stopSelf()
  }
 }

 override fun onDestroy(){
  connecting?.cancel();room?.release();room=null
  scope.cancel();super.onDestroy()
 }
}
