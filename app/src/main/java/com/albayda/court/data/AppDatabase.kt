package com.albayda.court.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class CourtPost(
    val id: String,
    val author: String,
    val details: String,
    val imagePath: String,
    val timestamp: String
)

data class SyncNotification(
    val id: String,
    val title: String,
    val body: String,
    val timestamp: String,
    val isRead: Boolean = false
)

data class SyncEvent(
    val id: String,
    val type: String,
    val payload: String,
    val createdAt: String
)

class AppDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE posts (" +
                    "id TEXT PRIMARY KEY, author TEXT, details TEXT, image_path TEXT, timestamp TEXT)"
        )
        db.execSQL(
            "CREATE TABLE news (" +
                    "id INTEGER PRIMARY KEY CHECK (id = 1), text TEXT, timestamp TEXT)"
        )
        db.execSQL(
            "CREATE TABLE events (" +
                    "id TEXT PRIMARY KEY, type TEXT, payload TEXT, created_at TEXT, synced INTEGER DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE notifications (" +
                    "id TEXT PRIMARY KEY, title TEXT, body TEXT, timestamp TEXT, is_read INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE applied_events (id TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS applied_events (id TEXT PRIMARY KEY)")
        }
    }

    fun insertPost(post: CourtPost) {
        val values = ContentValues().apply {
            put("id", post.id)
            put("author", post.author)
            put("details", post.details)
            put("image_path", post.imagePath)
            put("timestamp", post.timestamp)
        }
        writableDatabase.insertWithOnConflict("posts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deletePost(id: String) {
        writableDatabase.delete("posts", "id = ?", arrayOf(id))
    }

    fun getPosts(): List<CourtPost> {
        return readableDatabase.rawQuery(
            "SELECT id, author, details, image_path, timestamp FROM posts ORDER BY timestamp DESC", null
        ).use { c ->
            val out = mutableListOf<CourtPost>()
            while (c.moveToNext()) {
                out.add(
                    CourtPost(
                        id = c.getString(0),
                        author = c.getString(1) ?: "",
                        details = c.getString(2) ?: "",
                        imagePath = c.getString(3) ?: "",
                        timestamp = c.getString(4) ?: ""
                    )
                )
            }
            out
        }
    }

    fun setNews(text: String, timestamp: String) {
        val values = ContentValues().apply {
            put("id", 1)
            put("text", text)
            put("timestamp", timestamp)
        }
        writableDatabase.insertWithOnConflict("news", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getNews(): Pair<String, String> {
        return readableDatabase.rawQuery("SELECT text, timestamp FROM news WHERE id = 1", null).use { c ->
            if (c.moveToFirst()) Pair(c.getString(0) ?: "", c.getString(1) ?: "")
            else Pair("", "")
        }
    }

    fun queueEvent(event: SyncEvent) {
        val values = ContentValues().apply {
            put("id", event.id)
            put("type", event.type)
            put("payload", event.payload)
            put("created_at", event.createdAt)
            put("synced", 0)
        }
        writableDatabase.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun pendingEvents(): List<SyncEvent> {
        return readableDatabase.rawQuery(
            "SELECT id, type, payload, created_at FROM events WHERE synced = 0 ORDER BY created_at ASC", null
        ).use { c ->
            val out = mutableListOf<SyncEvent>()
            while (c.moveToNext()) {
                out.add(SyncEvent(c.getString(0), c.getString(1), c.getString(2) ?: "{}", c.getString(3)))
            }
            out
        }
    }

    fun markSynced(id: String) {
        val values = ContentValues().apply { put("synced", 1) }
        writableDatabase.update("events", values, "id = ?", arrayOf(id))
    }

    fun addNotification(notification: SyncNotification) {
        val values = ContentValues().apply {
            put("id", notification.id)
            put("title", notification.title)
            put("body", notification.body)
            put("timestamp", notification.timestamp)
            put("is_read", if (notification.isRead) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("notifications", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getNotifications(): List<SyncNotification> {
        return readableDatabase.rawQuery(
            "SELECT id, title, body, timestamp, is_read FROM notifications ORDER BY timestamp DESC LIMIT 100", null
        ).use { c ->
            val out = mutableListOf<SyncNotification>()
            while (c.moveToNext()) {
                out.add(
                    SyncNotification(
                        id = c.getString(0),
                        title = c.getString(1) ?: "",
                        body = c.getString(2) ?: "",
                        timestamp = c.getString(3) ?: "",
                        isRead = c.getInt(4) == 1
                    )
                )
            }
            out
        }
    }

    fun markNotificationsRead() {
        val values = ContentValues().apply { put("is_read", 1) }
        writableDatabase.update("notifications", values, null, null)
    }

    fun getMeta(key: String, default: String = ""): String {
        return readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: default else default
        }
    }

    fun setMeta(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun isEventApplied(id: String): Boolean {
        if (id.isBlank()) return false
        return readableDatabase.rawQuery(
            "SELECT 1 FROM applied_events WHERE id = ?", arrayOf(id)
        ).use { it.moveToFirst() }
    }

    fun markEventApplied(id: String) {
        if (id.isBlank()) return
        val values = ContentValues().apply { put("id", id) }
        writableDatabase.insertWithOnConflict("applied_events", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    companion object {
        private const val DB_NAME = "albayda_sync.db"
        private const val DB_VERSION = 2
    }
}
