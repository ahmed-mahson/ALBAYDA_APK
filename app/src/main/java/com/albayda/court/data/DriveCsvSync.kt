package com.albayda.court.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Syncs users, posts (announcements), and news ticker with a Google Drive CSV file.
 *
 * Read: downloads CSV from Drive (public/viewable link).
 * Write: POSTs full CSV to a configured endpoint (e.g. Google Apps Script Web App)
 *        that updates the Drive file. If no endpoint is configured, changes remain local
 *        and are exported on next successful push.
 */
object DriveCsvSync {
    private const val PREFS = "app_prefs"
    private const val KEY_FILE_ID = "drive_csv_sync_file_id"
    private const val KEY_WRITE_URL = "drive_csv_sync_write_url"
    private const val KEY_LAST_PULL = "drive_csv_sync_last_pull"
    private const val DEFAULT_FILE_ID = "1d_3-M6wTNOcUB1bCOTILh4di6XFGlgCT"
    private const val DEFAULT_WRITE_URL = "https://script.google.com/macros/s/AKfycbxT8sxQuJ5ew0wr1CVs3JdLQ7RdBthM7tYk6Mc1G7oPmAvch9I4mEwnm57RyHH-J0VQaw/exec"

    private lateinit var appContext: Context
    private val executor = Executors.newSingleThreadExecutor()
    private var scheduler: ScheduledExecutorService? = null
    private val scheduled = AtomicBoolean(false)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val HEADER = listOf(
        "record_type", "mobile", "username", "password_hash", "is_approved", "is_admin",
        "created_at", "updated_at", "post_id", "author", "details", "image_base64",
        "post_timestamp", "news_text", "deleted"
    )

    fun init(context: Context) {
        if (::appContext.isInitialized) {
            schedulePeriodic()
            return
        }
        appContext = context.applicationContext
        schedulePeriodic()
    }

    private fun schedulePeriodic() {
        if (scheduled.getAndSet(true)) return
        scheduler = Executors.newSingleThreadScheduledExecutor().also { s ->
            s.scheduleWithFixedDelay({
                runCatching { refreshAsync() }
            }, 30, 60, TimeUnit.SECONDS)
        }
    }

    fun fileId(): String = prefs().getString(KEY_FILE_ID, DEFAULT_FILE_ID)?.trim().orEmpty().ifBlank { DEFAULT_FILE_ID }
    fun setFileId(id: String) { prefs().edit().putString(KEY_FILE_ID, id.trim().ifBlank { DEFAULT_FILE_ID }).apply() }

    fun writeUrl(): String = prefs().getString(KEY_WRITE_URL, "")?.trim().orEmpty().ifBlank { DEFAULT_WRITE_URL }
    fun setWriteUrl(url: String) { prefs().edit().putString(KEY_WRITE_URL, url.trim()).apply() }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun refreshAsync(onDone: (() -> Unit)? = null) {
        if (!::appContext.isInitialized) return
        executor.execute {
            runCatching { pull() }
            runCatching { pushIfConfigured() }
            onDone?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
        }
    }

    fun pushAsync() {
        if (!::appContext.isInitialized) return
        executor.execute { runCatching { pushIfConfigured() } }
    }

    @Synchronized
    private fun pull() {
        val csv = downloadCsv(fileId()) ?: return
        val records = parseCsv(csv)
        if (records.isEmpty()) return
        var changed = false

        val users = records.filter { it["record_type"] == "user" }
        for (r in users) {
            val mobile = r["mobile"].orEmpty().trim()
            if (mobile.isBlank()) continue
            val username = r["username"].orEmpty()
            val passwordHash = r["password_hash"].orEmpty()
            val isApproved = r["is_approved"].orEmpty() == "1" || r["is_approved"].equals("true", true)
            val isAdmin = r["is_admin"].orEmpty() == "1" || r["is_admin"].equals("true", true)
            val createdAt = r["created_at"].orEmpty()
            val updatedAt = r["updated_at"].orEmpty().ifBlank { createdAt }
            val deleted = r["deleted"].orEmpty() == "1" || r["deleted"].equals("true", true)

            val metaKey = "drive_csv_user_ts_$mobile"
            val prev = SyncManager.db.getMeta(metaKey)
            if (updatedAt.isNotBlank() && prev.isNotBlank() && updatedAt <= prev) continue
            if (updatedAt.isNotBlank()) SyncManager.db.setMeta(metaKey, updatedAt)

            val userChanged = AuthManager.withRemoteApply {
                val existing = AuthManager.findByMobile(mobile)
                if (deleted) {
                    if (existing != null && !AuthManager.isPrimaryAdmin(existing)) {
                        AuthManager.rejectUser(mobile)
                        true
                    } else false
                } else {
                    AuthManager.upsertRemoteUser(username, mobile, passwordHash, createdAt)
                    val after = AuthManager.findByMobile(mobile)
                    if (after != null && !AuthManager.isPrimaryAdmin(after)) {
                        AuthManager.setApproved(mobile, isApproved)
                    }
                    // preserve admin flag if explicitly provided and not primary-admin conflict
                    if (isAdmin && after != null && !after.isAdmin) {
                        val all = AuthManager.getUsers().toMutableList()
                        val idx = all.indexOfFirst { it.mobile == mobile }
                        if (idx >= 0) {
                            all[idx] = all[idx].copy(isAdmin = true, isApproved = true)
                            saveUsersReflectively(all)
                        }
                    }
                    true
                }
            }
            if (userChanged) changed = true
        }

        val posts = records.filter { it["record_type"] == "post" }
        for (r in posts) {
            val postId = r["post_id"].orEmpty().trim()
            if (postId.isBlank()) continue
            val updatedAt = r["updated_at"].orEmpty().ifBlank { r["post_timestamp"].orEmpty() }
            val metaKey = "drive_csv_post_ts_$postId"
            val prev = SyncManager.db.getMeta(metaKey)
            if (updatedAt.isNotBlank() && prev.isNotBlank() && updatedAt <= prev) continue
            if (updatedAt.isNotBlank()) SyncManager.db.setMeta(metaKey, updatedAt)

            val deleted = r["deleted"].orEmpty() == "1" || r["deleted"].equals("true", true)
            if (deleted) {
                SyncManager.db.deletePost(postId)
                SyncManager.db.setMeta("deleted_post_$postId", updatedAt.ifBlank { nowIso() })
                changed = true
            } else {
                val details = r["details"].orEmpty()
                val author = r["author"].orEmpty().ifBlank { "محكمة البيضاء" }
                val timestamp = r["post_timestamp"].orEmpty().ifBlank { updatedAt.ifBlank { nowIso() } }
                val imagePath = saveImageBase64(postId, r["image_base64"].orEmpty())
                val existingPath = SyncManager.db.getPosts().firstOrNull { it.id == postId }?.imagePath.orEmpty()
                SyncManager.db.insertPost(CourtPost(postId, author, details, imagePath.ifBlank { existingPath }, timestamp))
                changed = true
            }
        }

        val newsRows = records.filter { it["record_type"] == "news" }
        val latestNews = newsRows.maxByOrNull { it["updated_at"].orEmpty().ifBlank { it["post_timestamp"].orEmpty() } }
        if (latestNews != null) {
            val text = latestNews["news_text"].orEmpty().ifBlank { latestNews["text"].orEmpty() }
            val ts = latestNews["updated_at"].orEmpty().ifBlank { latestNews["post_timestamp"].orEmpty().ifBlank { nowIso() } }
            val prev = SyncManager.db.getMeta("drive_csv_news_ts")
            if (text.isNotBlank() && (prev.isBlank() || ts >= prev)) {
                SyncManager.db.setMeta("drive_csv_news_ts", ts)
                SyncManager.db.setNews(text, ts)
                prefs().edit().putString("news_item", text).apply()
                changed = true
            }
        }

        prefs().edit().putLong(KEY_LAST_PULL, System.currentTimeMillis()).apply()
        if (changed) {
            runCatching {
                val intent = android.content.Intent("com.albayda.court.SYNC_UPDATED")
                appContext.sendBroadcast(intent)
            }
        }
    }

    @Synchronized
    private fun pushIfConfigured() {
        val endpoint = writeUrl()
        if (endpoint.isBlank()) return
        val csv = exportCsv()
        postCsv(endpoint, csv)
    }

    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",")).append("\n")

        val users = AuthManager.getUsers()
        for (u in users) {
            val row = mutableMapOf<String, String>()
            row["record_type"] = "user"
            row["mobile"] = u.mobile
            row["username"] = u.username
            row["password_hash"] = u.passwordHash
            row["is_approved"] = if (u.isApproved) "1" else "0"
            row["is_admin"] = if (u.isAdmin) "1" else "0"
            row["created_at"] = u.createdAt
            row["updated_at"] = SyncManager.db.getMeta("drive_csv_user_ts_${u.mobile}").ifBlank { u.createdAt.ifBlank { nowIso() } }
            row["deleted"] = "0"
            sb.append(HEADER.joinToString(",") { csvEscape(row[it].orEmpty()) }).append("\n")
        }

        val posts = SyncManager.getPosts()
        for (p in posts) {
            val row = mutableMapOf<String, String>()
            row["record_type"] = "post"
            row["post_id"] = p.id
            row["author"] = p.author
            row["details"] = p.details
            row["post_timestamp"] = p.timestamp
            row["updated_at"] = SyncManager.db.getMeta("drive_csv_post_ts_${p.id}").ifBlank { p.timestamp.ifBlank { nowIso() } }
            row["image_base64"] = imageToBase64(p.imagePath)
            row["deleted"] = "0"
            sb.append(HEADER.joinToString(",") { csvEscape(row[it].orEmpty()) }).append("\n")
        }

        val news = SyncManager.getNews()
        if (news.first.isNotBlank()) {
            val row = mutableMapOf<String, String>()
            row["record_type"] = "news"
            row["news_text"] = news.first
            row["post_timestamp"] = news.second
            row["updated_at"] = news.second.ifBlank { nowIso() }
            sb.append(HEADER.joinToString(",") { csvEscape(row[it].orEmpty()) }).append("\n")
        }

        val deletedUsers = deletedUserTombstones()
        for ((mobile, ts) in deletedUsers) {
            if (users.any { it.mobile == mobile }) continue
            val row = mutableMapOf<String, String>()
            row["record_type"] = "user"
            row["mobile"] = mobile
            row["updated_at"] = ts
            row["deleted"] = "1"
            sb.append(HEADER.joinToString(",") { csvEscape(row[it].orEmpty()) }).append("\n")
        }
        return sb.toString()
    }

    private fun deletedUserTombstones(): Map<String, String> {
        return try {
            val raw = SyncManager.db.getMeta("drive_csv_deleted_users", "{}")
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { k -> obj.optString(k, "") }.filterValues { it.isNotBlank() }
        } catch (_: Exception) { emptyMap() }
    }

    fun markUserDeleted(mobile: String, ts: String = nowIso()) {
        if (mobile.isBlank()) return
        try {
            val raw = SyncManager.db.getMeta("drive_csv_deleted_users", "{}")
            val obj = JSONObject(raw)
            obj.put(mobile, ts)
            SyncManager.db.setMeta("drive_csv_deleted_users", obj.toString())
        } catch (_: Exception) { }
    }

    private fun saveUsersReflectively(users: List<User>) {
        // AuthManager.saveUsers is private; update through public API by re-saving via prefs hack is unsafe.
        // Instead: upsert each and set flags via available methods; admin flag change is rare.
        // Fallback: write directly to AuthManager prefs if accessible isn't possible.
        try {
            val prefsField = AuthManager::class.java.getDeclaredField("prefs")
            prefsField.isAccessible = true
            val p = prefsField.get(AuthManager) as android.content.SharedPreferences
            val gsonField = AuthManager::class.java.getDeclaredField("gson")
            gsonField.isAccessible = true
            val gson = gsonField.get(AuthManager) as com.google.gson.Gson
            p.edit().putString("users_list", gson.toJson(users)).apply()
        } catch (_: Exception) { }
    }

    private fun nowIso(): String = iso.format(Date())

    private fun saveImageBase64(postId: String, b64: String): String {
        if (b64.isBlank()) return ""
        return try {
            val bytes = Base64.decode(b64.substringAfter(",").trim(), Base64.DEFAULT)
            val dir = java.io.File(appContext.filesDir, "posts").apply { mkdirs() }
            val f = java.io.File(dir, "drive_${postId}.jpg")
            f.writeBytes(bytes)
            f.absolutePath
        } catch (_: Exception) { "" }
    }

    private fun imageToBase64(path: String): String {
        if (path.isBlank()) return ""
        return try {
            val f = java.io.File(path)
            if (!f.exists() || f.length() > 700_000) return ""
            Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    private fun downloadCsv(fileId: String): String? {
        val urls = listOf(
            "https://drive.google.com/uc?export=download&id=$fileId",
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
        )
        for (url in urls) {
            try {
                val text = httpGet(url)
                if (text.isNullOrBlank()) continue
                val trimmed = text.trimStart()
                if (trimmed.startsWith("<!DOCTYPE", true) || trimmed.startsWith("<html", true)) {
                    val token = Regex("confirm=([0-9A-Za-z_-]+)").find(text)?.groupValues?.get(1)
                    if (!token.isNullOrBlank()) {
                        val text2 = httpGet("$url&confirm=$token")
                        if (!text2.isNullOrBlank() && !text2.trimStart().startsWith("<")) return text2
                    }
                    continue
                }
                return text
            } catch (_: Exception) { }
        }
        return null
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally { conn.disconnect() }
    }

    private fun postCsv(endpoint: String, csv: String) {
        try {
            var currentUrl = endpoint
            var method = "POST"
            var body: String? = csv
            for (i in 0..3) {
                val conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.requestMethod = method
                conn.connectTimeout = 20000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                }
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                val response = runCatching {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                conn.disconnect()
                if (code in 300..399 && !location.isNullOrBlank()) {
                    currentUrl = location
                    method = "GET"
                    body = null
                    continue
                }
                if (code in 200..299) return
                // non-success, stop retrying
                return
            }
        } catch (_: Exception) { }
    }

    private fun csvEscape(value: String): String {
        val v = value.replace("\r\n", "\n").replace("\r", "\n")
        return if (v.contains(',') || v.contains('"') || v.contains('\n')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v
    }

    private fun parseCsv(csv: String): List<Map<String, String>> {
        val rows = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < csv.length) {
            val c = csv[i]
            when {
                inQuotes && c == '"' && i + 1 < csv.length && csv[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { current.add(sb.toString()); sb.setLength(0) }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    if (c == '\r' && i + 1 < csv.length && csv[i + 1] == '\n') i++
                    current.add(sb.toString()); sb.setLength(0)
                    if (current.any { it.isNotBlank() }) rows.add(current.toList())
                    current.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || current.isNotEmpty()) {
            current.add(sb.toString())
            if (current.any { it.isNotBlank() }) rows.add(current.toList())
        }
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().trimStart('\uFEFF').lowercase(Locale.ROOT) }
        return rows.drop(1).map { row ->
            header.mapIndexed { idx, h -> h to row.getOrElse(idx) { "" } }.toMap()
        }
    }
}
