package com.dominion.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatLine(val role: String, val text: String, val createdAt: Long)

/** Simple account-isolated local conversation mirror; capped to 200 turns per account. */
class ConversationStore(context: Context) :
    SQLiteOpenHelper(context, "dominion.native.messages.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "account_id TEXT NOT NULL, role TEXT NOT NULL," +
                "content TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_messages_account ON messages(account_id,id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema migrations must preserve conversations; never drop the table.
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
