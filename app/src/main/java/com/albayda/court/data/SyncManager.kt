package com.albayda.court.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * One shared database for every device.
 *
 * Events are published as retained MQTT messages, which remain on the broker
 * until replaced and are replayed to any device that connects later. Local
 * SQLite is the working copy on each device; the retained event topics are the
 * single shared source of truth. An optional REST endpoint is still supported
 * for organisations that want to host their own server.
 */
object SyncManager {
    private const val PREF_NAME = "app_prefs"
    private const val KEY_URL = "sync_base_url"
    private const val KEY_TOKEN = "sync_token"

    private lateinit var appContext: Context
    lateinit var db: AppDatabase
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private var pollingStarted = false

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        db = AppDatabase(appContext)
        val dir = File(appContext.filesDir, "posts")
        if (!dir.exists()) dir.mkdirs()

        MqttTransport.start(
            context = appContext,
            onEvent = { json ->
                runCatching {
                    applyEvent(JSONObject(json))
                    notifyListeners()
                }
            },
            onReady = { publishPending() },
            onState = { /* state is exposed through syncState() */ }
        )
        startPolling()
    }

    /** Always enabled — no setup or admin action is required. */
    fun configured(): Boolean = true

    fun endpoint(): String = "مزامنة تلقائية مشتركة (MQTT)"

    fun syncState(): String = if (::appContext.isInitialized) MqttTransport.lastState else "غير مفعّلة"

    /** Optional self-hosted REST endpoint; the app works without it. */
    fun configure(baseUrl: String, token: String = "") {
        prefs().edit().putString(KEY_URL, baseUrl.trim().trimEnd('/')).putString(KEY_TOKEN, token.trim()).apply()
        refresh()
    }

    private fun token(): String = prefs().getString(KEY_TOKEN, "")?.trim() ?: ""

    private fun prefs() = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post { listeners.forEach { runCatching { it() } } }
    }

    fun now(): String = timeFormat.format(Date())

    fun addPost(author: String, details: String, imageBytes: ByteArray?, imagePath: String? = null): String {
        val id = UUID.randomUUID().toString()
        val ts = now()
        val path = imagePath ?: saveImage(id, imageBytes)
        val post = CourtPost(id, author, details, path, ts)
        db.insertPost(post)
        val payload = JSONObject().apply {
            put("id", id)
            put("author", author)
            put("details", details)
            put("timestamp", ts)
            if (imageBytes != null) put("image", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
        }
        queue("post", payload, ts)
        runCatching { FirebaseSync.publishAnnouncement(post, imageBytes) }
        runCatching { DriveCsvSync.pushAsync() }
        notifyListeners()
        return id
    }

    fun deletePost(id: String) {
        db.deletePost(id)
        db.setMeta("deleted_post_$id", now())
        queue("post_delete", JSONObject().put("id", id), now())
        runCatching { FirebaseSync.deleteAnnouncement(id) }
        runCatching { DriveCsvSync.pushAsync() }
        refresh()
        notifyListeners()
    }

    fun setNews(text: String) {
        val ts = now()
        db.setNews(text, ts)
        queue("news", JSONObject().put("text", text).put("timestamp", ts), ts)
        runCatching { FirebaseSync.publishTicker(text) }
        runCatching { DriveCsvSync.pushAsync() }
        notifyListeners()
    }

    fun notifyUserRegistered(username: String, mobile: String) {
        val ts = now()
        val hash = AuthManager.findByMobile(mobile)?.passwordHash.orEmpty()
        queue(
            "user_register",
            JSONObject()
                .put("username", username)
                .put("mobile", mobile)
                .put("password_hash", hash)
                .put("created_at", ts),
            ts
        )
        runCatching { FirebaseSync.publishUserRegistered(username, mobile) }
        runCatching { DriveCsvSync.pushAsync() }
        refresh()
    }

    fun notifyUserApproved(mobile: String, approved: Boolean) {
        val ts = now()
        queue(
            "user_approved",
            JSONObject().put("mobile", mobile).put("approved", approved).put("timestamp", ts),
            ts
        )
        runCatching { FirebaseSync.publishUserApproved(mobile, approved) }
        runCatching { DriveCsvSync.pushAsync() }
        refresh()
    }

    /** Broadcasts a username/password change so every device can log in with the new data. */
    fun notifyUserUpdated(username: String, mobile: String, passwordHash: String) {
        val ts = now()
        queue(
            "user_updated",
            JSONObject()
                .put("username", username)
                .put("mobile", mobile)
                .put("password_hash", passwordHash)
                .put("timestamp", ts),
            ts
        )
        runCatching { FirebaseSync.publishUserUpdated(username, mobile, passwordHash) }
        runCatching { DriveCsvSync.pushAsync() }
        refresh()
    }

    fun notifyUserDeleted(mobile: String) {
        val ts = now()
        // Rejected/deleted users are removed locally; mirror deletion to Firestore/Drive sync.
        runCatching { FirebaseSync.publishUserDeleted(mobile) }
        db.setMeta("user_event_ts_$mobile", ts)
        runCatching { DriveCsvSync.pushAsync() }
        refresh()
    }

    fun getPosts(): List<CourtPost> = db.getPosts()

    fun getNews(): Pair<String, String> = db.getNews()

    fun notifications(): List<SyncNotification> = db.getNotifications()

    fun markNotificationsRead() = db.markNotificationsRead()

    fun refresh() {
        if (!::appContext.isInitialized) return
        executor.execute {
            runCatching { syncNow() }
            runCatching { DriveCsvSync.refreshAsync() }
        }
    }

    private fun queue(type: String, payload: JSONObject, timestamp: String) {
        val event = SyncEvent(UUID.randomUUID().toString(), type, payload.toString(), timestamp)
        db.queueEvent(event)
        MqttTransport.publish(event.id, event.type, event.payload, event.createdAt)
    }

    /** Replays anything that was created while the broker was unavailable. */
    private fun publishPending() {
        db.pendingEvents().forEach { event ->
            MqttTransport.publish(event.id, event.type, event.payload, event.createdAt)
        }
    }

    private fun saveImage(id: String, bytes: ByteArray?): String {
        if (bytes == null) return ""
        return try {
            val file = File(File(appContext.filesDir, "posts"), "$id.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (_: Exception) {
            ""
        }
    }

    private fun startPolling() {
        if (pollingStarted) return
        pollingStarted = true
        executor.execute {
            while (true) {
                runCatching { syncNow() }
                Thread.sleep(15_000)
            }
        }
    }

    /** Optional REST backend; skipped when not configured because MQTT is automatic. */
    private fun syncNow() {
        val base = prefs().getString(KEY_URL, "")?.trim()?.trimEnd('/') ?: ""
        if (base.isBlank()) return

        val pending = db.pendingEvents()
        if (pending.isNotEmpty()) {
            val array = JSONArray()
            pending.forEach { event ->
                array.put(
                    JSONObject()
                        .put("id", event.id)
                        .put("type", event.type)
                        .put("payload", JSONObject(event.payload))
                        .put("created_at", event.createdAt)
                )
            }
            if (postJson("$base/events", array.toString())) {
                pending.forEach { db.markSynced(it.id) }
            }
        }

        val since = db.getMeta("last_pull", "1970-01-01T00:00:00.000Z")
        val response = getText("$base/events?since=${URLEncoder.encode(since, "UTF-8")}")
        val incoming = JSONArray(response)
        var maxTs = since
        for (i in 0 until incoming.length()) {
            val event = incoming.optJSONObject(i) ?: continue
            applyEvent(event)
            val ts = event.optString("created_at", event.optString("timestamp", ""))
            if (ts > maxTs) maxTs = ts
        }
        if (incoming.length() > 0) {
            db.setMeta("last_pull", maxTs)
            notifyListeners()
        }
    }

    /** Applies one event exactly once, regardless of how many times it is delivered. */
    private fun applyEvent(event: JSONObject) {
        val id = event.optString("id")
        if (id.isBlank() || db.isEventApplied(id)) return
        val type = event.optString("type")
        val payload = event.optJSONObject("payload") ?: JSONObject()
        val ts = event.optString("created_at", event.optString("timestamp", ""))

        when (type) {
            "post" -> {
                val postId = payload.optString("id")
                if (postId.isNotBlank() && db.getMeta("deleted_post_$postId").isBlank()) {
                    applyPost(payload)
                }
            }
            "post_delete" -> {
                val postId = payload.optString("id")
                if (postId.isNotBlank()) {
                    db.setMeta("deleted_post_$postId", ts.ifBlank { now() })
                    db.deletePost(postId)
                }
            }
            "news" -> {
                val text = payload.optString("text")
                val newsTs = payload.optString("timestamp", ts)
                if (text.isNotBlank() && newsTs >= db.getNews().second) {
                    db.setNews(text, newsTs.ifBlank { now() })
                }
            }
            "user_register" -> {
                val mobile = payload.optString("mobile")
                val username = payload.optString("username")
                if (mobile.isNotBlank() && isNewerUserEvent(mobile, ts)) {
                    AuthManager.upsertRemoteUser(
                        username,
                        mobile,
                        payload.optString("password_hash"),
                        payload.optString("created_at", ts)
                    )
                    db.addNotification(
                        SyncNotification(
                            id = "reg-$mobile-$ts",
                            title = "تسجيل مستخدم جديد",
                            body = "المستخدم: $username\nالجوال: $mobile",
                            timestamp = ts.ifBlank { now() }
                        )
                    )
                }
            }
            "user_approved" -> {
                val mobile = payload.optString("mobile")
                if (mobile.isNotBlank() && isNewerUserEvent(mobile, ts)) {
                    val approved = payload.optBoolean("approved")
                    AuthManager.setApproved(mobile, approved)
                    db.addNotification(
                        SyncNotification(
                            id = "apr-$mobile-$ts",
                            title = if (approved) "تم اعتماد حسابك" else "تم رفض حسابك",
                            body = "تم تحديث حالة الحساب رقم $mobile",
                            timestamp = ts.ifBlank { now() }
                        )
                    )
                }
            }
            "user_updated" -> {
                val mobile = payload.optString("mobile")
                if (mobile.isNotBlank() && isNewerUserEvent(mobile, ts)) {
                    AuthManager.upsertRemoteUser(
                        payload.optString("username"),
                        mobile,
                        payload.optString("password_hash"),
                        payload.optString("created_at", ts)
                    )
                }
            }
        }
        db.markEventApplied(id)
    }

    private fun isNewerUserEvent(mobile: String, ts: String): Boolean {
        val key = "user_event_ts_$mobile"
        val previous = db.getMeta(key)
        if (ts.isBlank() || ts >= previous) {
            db.setMeta(key, ts)
            return true
        }
        return false
    }

    private fun applyPost(payload: JSONObject) {
        val id = payload.optString("id")
        if (id.isBlank()) return
        val existingPath = db.getPosts().firstOrNull { it.id == id }?.imagePath.orEmpty()
        val image = payload.optString("image", "")
        val path = if (image.isNotBlank()) {
            try {
                saveImage(id, Base64.decode(image, Base64.DEFAULT))
            } catch (_: Exception) {
                existingPath
            }
        } else existingPath
        db.insertPost(
            CourtPost(
                id = id,
                author = payload.optString("author"),
                details = payload.optString("details"),
                imagePath = path,
                timestamp = payload.optString("timestamp")
            )
        )
    }

    private fun postJson(url: String, body: String): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token().isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${token()}")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun getText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (token().isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${token()}")
            if (conn.responseCode !in 200..299) return "[]"
            conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (_: Exception) {
            "[]"
        } finally {
            conn.disconnect()
        }
    }
}
