package com.dominion.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatLine(val role: String, val text: String, val createdAt: Long)

/** Simple account-isolated local conversation mirror; capped to 200 turns per account. */
class ConversationStore(context: Context) :
    SQLiteOpenHelper(context, "dominion.native.messages.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "account_id TEXT NOT NULL, role TEXT NOT NULL," +
                "content TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_messages_account ON messages(account_id,id)")
        createProfiles(db)
        createPcTurns(db)
    }

    private fun createProfiles(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS profiles (" +
            "account_id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL)")
    }

    private fun createPcTurns(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pc_turns (" +
            "account_id TEXT NOT NULL, device_id TEXT NOT NULL," +
            "request_id TEXT NOT NULL, created_at INTEGER NOT NULL," +
            "data TEXT NOT NULL, PRIMARY KEY(account_id,device_id,request_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pc_turns_device " +
            "ON pc_turns(account_id,device_id,created_at,request_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Existing conversations are never dropped or rewritten.
        if (oldVersion < 2) createProfiles(db)
        if (oldVersion < 3) createPcTurns(db)
    }

    fun readProfile(account: String): AssistantProfile {
        readableDatabase.query("profiles", arrayOf("data"),
            "account_id=?", arrayOf(account), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) return AssistantProfile()
            return try {
                AssistantProfile.fromJson(org.json.JSONObject(cursor.getString(0)))
            } catch (_: Exception) {
                // Preserve the invalid saved value for manual recovery.
                AssistantProfile()
            }
        }
    }

    fun saveProfile(account: String, profile: AssistantProfile) {
        val valid = profile.validated()
        val values = ContentValues().apply {
            put("account_id", account)
            put("data", valid.asJson().toString())
        }
        writableDatabase.insertWithOnConflict(
            "profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun pcHistory(account: String, device: String): List<PcTurn> {
        val results = mutableListOf<PcTurn>()
        readableDatabase.query("pc_turns", arrayOf("data"),
            "account_id=? AND device_id=?", arrayOf(account,device),
            null,null,"created_at DESC,request_id DESC","200").use { cursor ->
            while(cursor.moveToNext()) {
                try {
                    val row = org.json.JSONObject(cursor.getString(0))
                    results.add(PcTurn(
                        requestId=row.getString("requestId"),
                        text=row.getString("text"),
                        status=row.getString("status"),
                        answer=row.optString("answer"),
                        createdAt=row.optLong("createdAt"),
                        kind=row.optString("kind"),
                        attachmentName=row.optString("attachmentName").ifBlank{null},
                        error=row.optString("error").ifBlank{null}
                    ))
                } catch (_: Exception) {
                    // Skip damaged cached records, preserve all other records.
                }
            }
        }
        return results.asReversed()
    }

    fun mirrorPcHistory(account: String, device: String, updates: List<PcTurn>) {
        if(updates.isEmpty()) return
        val db=writableDatabase
        db.beginTransaction()
        try {
            for(turn in updates.takeLast(200)) {
                if(!runCatching {
                    java.util.UUID.fromString(turn.requestId).toString() == turn.requestId
                }.getOrDefault(false))continue
                val json=org.json.JSONObject()
                    .put("requestId",turn.requestId)
                    .put("text",turn.text.take(6000))
                    .put("status",turn.status.take(40))
                    .put("answer",turn.answer.take(12000))
                    .put("createdAt",turn.createdAt)
                    .put("kind",turn.kind.take(40))
                    .put("attachmentName",turn.attachmentName ?: "")
                    .put("error",turn.error ?: "")
                val values=ContentValues().apply {
                    put("account_id",account);put("device_id",device)
                    put("request_id",turn.requestId)
                    put("created_at",turn.createdAt)
                    put("data",json.toString())
                }
                db.insertWithOnConflict("pc_turns",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.execSQL("DELETE FROM pc_turns WHERE account_id=? AND device_id=? AND " +
                "request_id NOT IN (SELECT request_id FROM pc_turns " +
                "WHERE account_id=? AND device_id=? " +
                "ORDER BY created_at DESC,request_id DESC LIMIT 200)",
                arrayOf(account,device,account,device))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun clearPcMirror(account: String, device: String) {
        writableDatabase.delete("pc_turns","account_id=? AND device_id=?",
            arrayOf(account,device))
    }

    fun history(account: String): List<ChatLine> {
        val result = mutableListOf<ChatLine>()
        readableDatabase.query(
            "messages", arrayOf("role", "content", "created_at"),
            "account_id=?", arrayOf(account), null, null, "id DESC", "200"
        ).use { c ->
            while (c.moveToNext()) {
                result.add(ChatLine(c.getString(0), c.getString(1), c.getLong(2)))
            }
        }
        return result.asReversed()
    }

    fun append(account: String, role: String, content: String) {
        require(role == "user" || role == "assistant")
        require(content.length <= 20_000)
        writableDatabase.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("account_id", account)
                put("role", role)
                put("content", content)
                put("created_at", System.currentTimeMillis())
            }
            writableDatabase.insertOrThrow("messages", null, values)
            writableDatabase.execSQL(
                "DELETE FROM messages WHERE account_id=? AND id NOT IN (" +
                    "SELECT id FROM messages WHERE account_id=? ORDER BY id DESC LIMIT 200)",
                arrayOf(account, account)
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun clear(account: String) {
        writableDatabase.delete("messages", "account_id=?", arrayOf(account))
    }
}
