package com.dominion.assistant.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dominion.assistant.MainActivity
import com.dominion.assistant.R
import com.dominion.assistant.data.Account
import com.dominion.assistant.data.AccountVault
import com.dominion.assistant.data.CloudApi
import com.dominion.assistant.data.CloudError
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val CHANNEL="dominion_assistant_alerts"
private const val PREFS="dominion.native.push.v1"
private const val WORK_NAME="dominion.native.push.refresh"

class NativePush(private val context: Context, private val api: CloudApi = CloudApi()) {
 private val settings=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
 fun configured():Boolean=FirebaseApp.getApps(context).isNotEmpty()
 fun enabled(account:Account):Boolean=
  settings.getBoolean("enabled",false)&&settings.getString("account",null)==account.id

 fun createChannel() {
  val manager=context.getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel(CHANNEL,"DOMINION 업무 알림",
   NotificationManager.IMPORTANCE_DEFAULT))
 }

 private suspend fun fcmToken():String=withContext(Dispatchers.IO) {
  Tasks.await(FirebaseMessaging.getInstance().token,20,TimeUnit.SECONDS)
 }

 suspend fun enable(account:Account) {
  check(configured()){"Firebase 프로젝트가 아직 연결되지 않았습니다."}
  if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(
    context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
   throw IllegalStateException("알림 권한을 허용해 주세요.")
  }
  val status=try {
   api.get("/v1/assistant/push/status",account.token)
  }catch(e:CloudError){
   if(e.statusCode==404 ||
     (e.statusCode==403&&e.message=="Permission denied or unknown route"))
    throw IllegalStateException("Cloud 서버에 Android 알림 기능이 아직 배포되지 않았습니다.")
   throw e
  }
  check(status.optBoolean("nativeReady",false)){
   "Cloud 서버의 FCM 전송 설정이 필요합니다."
  }
  createChannel()
  FirebaseMessaging.getInstance().isAutoInitEnabled=true
  val token=fcmToken()
  require(token.length in 40..400){"Invalid FCM token"}
  api.post("/v1/assistant/push/register",JSONObject().put("token","fcm:"+token),
    account.token)
  settings.edit().putBoolean("enabled",true).putString("account",account.id)
   .putString("token",token).apply()
 }

 suspend fun disable(account:Account) {
  val old=settings.getString("token",null)
  if(enabled(account)&&old!=null) {
   api.post("/v1/assistant/push/unregister",JSONObject().put("token","fcm:"+old),
     account.token)
  }
  settings.edit().clear().apply()
  if(configured())FirebaseMessaging.getInstance().isAutoInitEnabled=false
  WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
 }

 fun scheduleRefresh() {
  if(!settings.getBoolean("enabled",false))return
  val request=OneTimeWorkRequestBuilder<RefreshPushWorker>()
   .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
   .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
   .build()
  WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME,
   ExistingWorkPolicy.REPLACE,request)
 }
}

class RefreshPushWorker(appContext:Context,workerParams:WorkerParameters):
 CoroutineWorker(appContext,workerParams) {
 override suspend fun doWork():Result {
  val prefs=applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  if(!prefs.getBoolean("enabled",false))return Result.success()
  val account=withContext(Dispatchers.IO){AccountVault(applicationContext).load()}
    ?:return Result.failure()
  if(prefs.getString("account",null)!=account.id)return Result.success()
  if(FirebaseApp.getApps(applicationContext).isEmpty())return Result.failure()
  return try {
   val current=withContext(Dispatchers.IO){
    Tasks.await(FirebaseMessaging.getInstance().token,20,TimeUnit.SECONDS)
   }
   require(current.length in 40..400)
   val old=prefs.getString("token",null)
   val api=CloudApi()
   api.post("/v1/assistant/push/register",JSONObject().put("token","fcm:"+current),
    account.token)
   prefs.edit().putString("token",current).apply()
   if(old!=null&&old!=current)try {
    api.post("/v1/assistant/push/unregister",JSONObject().put("token","fcm:"+old),
     account.token)
   }catch(_:Exception){}
   Result.success()
  }catch(e:CloudError) {
   if(e.statusCode in setOf(400,401,403,409))Result.failure()
   else if(runAttemptCount<6)Result.retry() else Result.failure()
  }catch(_:Exception) {
   if(runAttemptCount<6)Result.retry() else Result.failure()
  }
 }
}

class DominionMessagingService:FirebaseMessagingService() {
 override fun onNewToken(token:String) {
  NativePush(this).scheduleRefresh()
 }

 override fun onMessageReceived(message:RemoteMessage) {
  val kind=message.data["kind"] ?: return
  if(message.data["assistantMessage"]!="true")return
  if(kind !in setOf("message","job","auth","mail"))return
  val prefs=getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  if(!prefs.getBoolean("enabled",false))return
  val owner=AccountVault(this).load() ?:return
  if(prefs.getString("account",null)!=owner.id)return
  if(Build.VERSION.SDK_INT>=33&&
   ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=
    PackageManager.PERMISSION_GRANTED)return
  val push=NativePush(this)
  push.createChannel()
  val open=PendingIntent.getActivity(this,50,
   Intent(this,MainActivity::class.java).putExtra("dominion_open_work",true)
    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
   PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  val detail=when(kind) {
   "message" -> "PC에서 새 메시지가 도착했습니다."
   "auth" -> "웹 업무에 확인 또는 로그인이 필요합니다."
   "mail" -> "새 메일 알림이 있습니다."
   else -> "업무 진행 상태를 확인하세요."
  }
  val notification=Notification.Builder(this,CHANNEL)
   .setSmallIcon(R.drawable.ic_dominion)
   .setContentTitle("DOMINION Assistant")
   .setContentText(detail)
   .setAutoCancel(true)
   .setContentIntent(open)
   .build()
  getSystemService(NotificationManager::class.java)
   .notify((System.currentTimeMillis()%100_000).toInt()+5000,notification)
 }
}
