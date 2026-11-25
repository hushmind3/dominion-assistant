package com.dominion.assistant.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.*
import com.dominion.assistant.MainActivity
import com.dominion.assistant.R
import com.dominion.assistant.data.AccountVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

data class LocalReminder(val id:String,val title:String,val time:Long)

object ReminderPolicy {
    fun valid(title:String,at:Long,now:Long=System.currentTimeMillis()):Boolean {
        val clean=title.trim()
        return clean.isNotBlank()&&clean.length<=120 &&
            !clean.any { it.isISOControl() } &&
            at > now+5_000 && at <= now+365L*24*60*60*1000
    }
}

private class ReminderDatabase(context:Context):
    SQLiteOpenHelper(context,"dominion.native.reminders.db",null,1) {
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE reminders ("+
            "account_id TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL,"+
            "due_at INTEGER NOT NULL, PRIMARY KEY(account_id,id))")
        db.execSQL("CREATE INDEX idx_reminders_owner ON reminders(account_id,due_at)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        // Preserve all owner reminders across future migrations.
    }
    fun list(account:String):List<LocalReminder> {
        val items=mutableListOf<LocalReminder>()
        readableDatabase.query("reminders",arrayOf("id","title","due_at"),
            "account_id=?",arrayOf(account),null,null,"due_at ASC","100").use { c ->
            while(c.moveToNext())items.add(LocalReminder(
                c.getString(0),c.getString(1),c.getLong(2)))
        }
        return items
    }
    fun get(account:String,id:String):LocalReminder? {
        readableDatabase.query("reminders",arrayOf("id","title","due_at"),
            "account_id=? AND id=?",arrayOf(account,id),null,null,null,"1").use { c ->
            return if(c.moveToFirst())
                LocalReminder(c.getString(0),c.getString(1),c.getLong(2)) else null
        }
    }
    fun put(account:String,item:LocalReminder) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT COUNT(*) FROM reminders WHERE account_id=?",
                arrayOf(account)).use { c ->
                if(c.moveToFirst()&&c.getInt(0)>=100)
                    throw IllegalArgumentException("예약 알림은 최대 100개입니다.")
            }
            db.insertOrThrow("reminders",null,ContentValues().apply {
                put("account_id",account)
                put("id",item.id)
                put("title",item.title)
                put("due_at",item.time)
            })
            db.setTransactionSuccessful()
        }finally { db.endTransaction() }
    }
    fun remove(account:String,id:String) {
        writableDatabase.delete("reminders","account_id=? AND id=?",arrayOf(account,id))
    }
}

private const val CHANNEL="dominion_native_reminders"
private fun workName(id:String)="dominion.reminder.$id"

class NativeReminders(private val context:Context) {
    suspend fun list(account:String):List<LocalReminder> = withContext(Dispatchers.IO) {
        ReminderDatabase(context).use { it.list(account) }
    }

    suspend fun schedule(account:String,title:String,dueAt:Long):LocalReminder =
        withContext(Dispatchers.IO) {
            require(ReminderPolicy.valid(title,dueAt)){
                "알림 내용(120자 이하)과 미래 예약 시간을 확인해 주세요."
            }
            val item=LocalReminder(UUID.randomUUID().toString(),title.trim(),dueAt)
            ReminderDatabase(context).use { it.put(account,item) }
            try {
                val input=workDataOf("account" to account,"id" to item.id)
                val request=OneTimeWorkRequestBuilder<NativeReminderWorker>()
                    .setInputData(input)
                    .setInitialDelay((dueAt-System.currentTimeMillis()).coerceAtLeast(0),
                        TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    workName(item.id),ExistingWorkPolicy.REPLACE,request
                ).result.get(12,TimeUnit.SECONDS)
                item
            }catch(e:Exception) {
                ReminderDatabase(context).use { it.remove(account,item.id) }
                throw IllegalStateException("알림 예약에 실패했습니다.",e)
            }
        }

    suspend fun cancel(account:String,id:String) = withContext(Dispatchers.IO) {
        require(UUID.fromString(id).toString()==id)
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        ReminderDatabase(context).use { it.remove(account,id) }
    }
}

class NativeReminderWorker(context:Context,params:WorkerParameters):
    CoroutineWorker(context,params) {
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        val account=inputData.getString("account")?:return@withContext Result.failure()
        val id=inputData.getString("id")?:return@withContext Result.failure()
        val record=ReminderDatabase(applicationContext).use{
            it.get(account,id)
        }?:return@withContext Result.success()
        val signed=AccountVault(applicationContext).load()
        if(signed?.id!=account){
            // Do not show an old account's reminder under another logged-in identity.
            ReminderDatabase(applicationContext).use{it.remove(account,id)}
            return@withContext Result.success()
        }
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL,"DOMINION 예약 알림",NotificationManager.IMPORTANCE_DEFAULT))
        if(Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(
            applicationContext,Manifest.permission.POST_NOTIFICATIONS)==
            PackageManager.PERMISSION_GRANTED) {
            val open=PendingIntent.getActivity(applicationContext,id.hashCode(),
                Intent(applicationContext,MainActivity::class.java)
                    .putExtra("dominion_open_work",true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification=Notification.Builder(applicationContext,CHANNEL)
                .setSmallIcon(R.drawable.ic_dominion)
                .setContentTitle("DOMINION 예약 알림")
                .setContentText(record.title)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(id.hashCode(),notification)
        }
        ReminderDatabase(applicationContext).use{it.remove(account,id)}
        Result.success()
    }
}
