package com.dominion.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatLine(val role: String, val text: String, val createdAt: Long)

/** Simple account-isolated local conversation mirror; capped to 200 turns per account. */
class ConversationStore(context: Context) :
    SQLiteOpenHelper(context, "dominion.native.messages.db", null, 5) {
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
        createErrands(db)
        createMailNotices(db)
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

    private fun createErrands(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS prepared_errands (" +
            "account_id TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL," +
            " state TEXT NOT NULL, created_at INTEGER NOT NULL," +
            " PRIMARY KEY(account_id,id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prepared_errands_account " +
            "ON prepared_errands(account_id,created_at)")
    }

    private fun createMailNotices(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS mail_notices (" +
            "account_id TEXT NOT NULL, alert_id TEXT NOT NULL," +
            " created_at INTEGER NOT NULL, PRIMARY KEY(account_id, alert_id))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Existing conversations are never dropped or rewritten.
        if (oldVersion < 2) createProfiles(db)
        if (oldVersion < 3) createPcTurns(db)
        if (oldVersion < 4) createErrands(db)
        if (oldVersion < 5) createMailNotices(db)
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

    fun errands(account: String): List<PreparedErrand> {
        val drafts = mutableListOf<PreparedErrand>()
        readableDatabase.query("prepared_errands",
            arrayOf("id", "payload", "state", "created_at"), "account_id=?",
            arrayOf(account), null, null, "created_at DESC", "30").use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0)
                val intent = AssistantTaskIntent.parse(rows.getString(1))
                val state = rows.getString(2)
                if (!runCatching { java.util.UUID.fromString(id).toString() == id }
                        .getOrDefault(false) ||
                    intent == null || !AssistantTaskIntent.persistable(intent) ||
                    state !in setOf("ready", "unknown", "submitted")) continue
                drafts.add(PreparedErrand(id, intent, rows.getLong(3), state))
            }
        }
        return drafts.asReversed()
    }

    fun saveErrand(account: String, plan: PreparedErrand) {
        require(AssistantTaskIntent.persistable(plan.intent))
        require(plan.state in setOf("ready", "unknown", "submitted"))
        require(java.util.UUID.fromString(plan.id).toString() == plan.id)
        val values = ContentValues().apply {
            put("account_id", account)
            put("id", plan.id)
            put("payload", AssistantTaskIntent.encode(plan.intent))
            put("state", plan.state)
            put("created_at", plan.createdAt)
        }
        writableDatabase.insertWithOnConflict("prepared_errands", null, values,
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateErrandState(account: String, id: String, state: String) {
        require(state in setOf("ready", "unknown", "submitted"))
        writableDatabase.update("prepared_errands",
            ContentValues().apply { put("state", state) },
            "account_id=? AND id=?", arrayOf(account, id))
    }

    /** One atomic transition prevents duplicate completion messages after app restarts. */
    fun completeErrand(account: String, id: String, resultText: String): Boolean {
        require(resultText.length in 1..20_000)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val taken = db.delete("prepared_errands",
                "account_id=? AND id=? AND state='submitted'", arrayOf(account,id))
            if (taken == 0) {
                db.setTransactionSuccessful()
                return false
            }
            val values = ContentValues().apply {
                put("account_id", account)
                put("role", "assistant")
                put("content", resultText)
                put("created_at", System.currentTimeMillis())
            }
            db.insertOrThrow("messages", null, values)
            db.execSQL(
                "DELETE FROM messages WHERE account_id=? AND id NOT IN (" +
                    "SELECT id FROM messages WHERE account_id=? ORDER BY id DESC LIMIT 200)",
                arrayOf(account, account)
            )
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun deleteErrand(account: String, id: String) {
        writableDatabase.delete("prepared_errands", "account_id=? AND id=?",
            arrayOf(account, id))
    }

    /** Mail events become conversation messages at most once per account, across restarts. */
    fun mirrorMailAlerts(account: String, alerts: List<MailAlert>): List<ChatLine> {
        val notes = mutableListOf<ChatLine>()
        val recent = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (alert in alerts.takeLast(8)) {
                if(alert.id.length !in 1..200 || alert.time < recent) continue
                val value = ContentValues().apply {
                    put("account_id", account)
                    put("alert_id", alert.id)
                    put("created_at", alert.time)
                }
                val inserted = db.insertWithOnConflict("mail_notices", null,
                    value, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted == -1L) continue
                val prefix = if(alert.important) "📬 중요 메일" else "📩 새 메일"
                val text = prefix + "\n보낸 사람: " + alert.from.take(180) +
                    "\n제목: " + alert.subject.take(220)
                val time = System.currentTimeMillis()
                db.insertOrThrow("messages", null, ContentValues().apply {
                    put("account_id", account)
                    put("role", "assistant")
                    put("content", text)
                    put("created_at", time)
                })
                notes.add(ChatLine("assistant", text, time))
            }
            db.execSQL(
                "DELETE FROM messages WHERE account_id=? AND id NOT IN (" +
                    "SELECT id FROM messages WHERE account_id=? ORDER BY id DESC LIMIT 200)",
                arrayOf(account, account)
            )
            db.execSQL("DELETE FROM mail_notices WHERE account_id=? AND created_at<?",
                arrayOf(account, System.currentTimeMillis() - 31L * 86400000))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return notes
    }

    fun clear(account: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", "account_id=?", arrayOf(account))
            db.delete("prepared_errands", "account_id=?", arrayOf(account))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
