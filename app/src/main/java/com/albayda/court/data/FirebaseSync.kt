package com.albayda.court.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Prepares and bridges Firebase services for real-time sync, offline cache,
 * and push-notification topics. All operations are best-effort so the app
 * continues to work with existing local/MQTT sync when Firebase is unavailable.
 */
object FirebaseSync {
    private lateinit var appContext: Context
    @Volatile private var started = false
    private val registrations = mutableListOf<ListenerRegistration>()
    private val tickerListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (started) return
        synchronized(lock) {
            if (started) return
            try {
                FirebaseApp.initializeApp(appContext)
            } catch (_: Exception) {
            }
            try {
                val db = FirebaseFirestore.getInstance()
                try {
                    @Suppress("DEPRECATION")
                    db.firestoreSettings = FirebaseFirestoreSettings.Builder(db.firestoreSettings)
                        .setPersistenceEnabled(true)
                        .build()
                } catch (_: Exception) {
                    // Already configured or not supported in this runtime state.
                }
                startListeners(db)
                ensureAuth()
                started = true
            } catch (_: Exception) {
                // Firebase config may be missing/invalid; keep local fallbacks active.
            }
        }
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic("court_updates") }
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic("announcements") }
    }

    private fun ensureAuth() {
        try {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously()
            }
        } catch (_: Exception) {
        }
    }

    private fun prefs() = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun addTickerListener(listener: (String) -> Unit) {
        tickerListeners.add(listener)
        val cached = prefs().getString("firestore_ticker_text", "").orEmpty()
        if (cached.isNotBlank()) runCatching { listener(cached) }
    }

    fun removeTickerListener(listener: (String) -> Unit) {
        tickerListeners.remove(listener)
    }

    private fun startListeners(db: FirebaseFirestore) {
        // Users/posts/news ticker sync is handled via DriveCsvSync (Google Drive CSV)
        // per app configuration. Firestore listeners for these collections are intentionally disabled.
    }

    private fun isNewerFsEvent(metaKey: String, ts: String): Boolean {
        return try {
            SyncManager.init(appContext)
            val prev = SyncManager.db.getMeta(metaKey)
            if (ts.isBlank() || ts >= prev) {
                SyncManager.db.setMeta(metaKey, ts)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun firstString(data: Map<String, Any?>?, vararg keys: String): String {
        if (data == null) return ""
        for (k in keys) {
            val v = data[k]
            if (v != null) {
                val s = v.toString().trim()
                if (s.isNotBlank() && s != "null") return s
            }
        }
        return ""
    }

    private fun cacheCollection(name: String, items: List<String>) {
        cacheDocuments(name, items)
    }

    private fun cacheDocuments(name: String, items: List<String>) {
        try {
            val arr = JSONArray()
            items.forEach { runCatching { arr.put(JSONObject(it)) }.onFailure { arr.put(it) } }
            prefs().edit().putString("firestore_cache_$name", arr.toString()).putLong("firestore_cache_${name}_ts", System.currentTimeMillis()).apply()
        } catch (_: Exception) {
        }
    }

    fun getCached(name: String): String = prefs().getString("firestore_cache_$name", "[]") ?: "[]"

    private fun materializeAnnouncementImage(id: String, data: Map<String, Any?>?): String {
        if (data == null) return ""
        val b64 = firstString(data, "imageBase64", "image_base64", "imageData")
        if (b64.isNotBlank()) {
            return try {
                val bytes = android.util.Base64.decode(b64.substringAfter(","), android.util.Base64.DEFAULT)
                val dir = File(appContext.filesDir, "posts").apply { mkdirs() }
                val file = File(dir, "fs_${id}.jpg")
                file.writeBytes(bytes)
                file.absolutePath
            } catch (_: Exception) {
                ""
            }
        }
        // If only a URL/path is provided, keep it in meta for future use; local image path remains empty.
        val url = firstString(data, "image", "imageUrl", "image_url")
        if (url.isNotBlank()) {
            runCatching { prefs().edit().putString("fs_image_url_$id", url).apply() }
        }
        return ""
    }

    fun publishTicker(text: String) {
        if (text.isBlank()) return
        runCatching {
            val db = FirebaseFirestore.getInstance()
            db.collection("settings").document("ticker")
                .set(mapOf("text" to text, "updatedAt" to com.google.firebase.Timestamp.now()), SetOptions.merge())
        }
    }

    fun publishAnnouncement(post: CourtPost, imageBytes: ByteArray? = null) {
        runCatching {
            val db = FirebaseFirestore.getInstance()
            val data = hashMapOf<String, Any>(
                "id" to post.id,
                "author" to post.author,
                "details" to post.details,
                "timestamp" to post.timestamp
            )
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                // Keep payload reasonably small for Firestore document limits.
                if (imageBytes.size <= 700_000) {
                    data["imageBase64"] = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                }
            }
            db.collection("announcements").document(post.id).set(data, SetOptions.merge())
        }
    }

    fun deleteAnnouncement(postId: String) {
        if (postId.isBlank()) return
        runCatching {
            FirebaseFirestore.getInstance().collection("announcements").document(postId).delete()
        }
    }

    fun publishUserRegistered(username: String, mobile: String) {
        if (mobile.isBlank()) return
        runCatching {
            AuthManager.init(appContext)
            val user = AuthManager.findByMobile(mobile)
            val data = hashMapOf<String, Any>(
                "mobile" to mobile,
                "username" to (user?.username ?: username),
                "passwordHash" to (user?.passwordHash ?: ""),
                "isApproved" to (user?.isApproved ?: false),
                "createdAt" to (user?.createdAt ?: SyncManager.now()),
                "updatedAt" to SyncManager.now(),
                "deleted" to false
            )
            FirebaseFirestore.getInstance().collection("users").document(mobile)
                .set(data, SetOptions.merge())
        }
    }

    fun publishUserApproved(mobile: String, approved: Boolean) {
        if (mobile.isBlank()) return
        runCatching {
            AuthManager.init(appContext)
            val user = AuthManager.findByMobile(mobile)
            val data = hashMapOf<String, Any>(
                "mobile" to mobile,
                "username" to (user?.username ?: ""),
                "passwordHash" to (user?.passwordHash ?: ""),
                "isApproved" to approved,
                "updatedAt" to SyncManager.now(),
                "deleted" to false
            )
            FirebaseFirestore.getInstance().collection("users").document(mobile)
                .set(data, SetOptions.merge())
        }
    }

    fun publishUserUpdated(username: String, mobile: String, passwordHash: String) {
        if (mobile.isBlank()) return
        runCatching {
            AuthManager.init(appContext)
            val user = AuthManager.findByMobile(mobile)
            val data = hashMapOf<String, Any>(
                "mobile" to mobile,
                "username" to (user?.username ?: username),
                "passwordHash" to passwordHash.ifBlank { user?.passwordHash ?: "" },
                "isApproved" to (user?.isApproved ?: false),
                "updatedAt" to SyncManager.now(),
                "deleted" to false
            )
            FirebaseFirestore.getInstance().collection("users").document(mobile)
                .set(data, SetOptions.merge())
        }
    }

    fun publishUserDeleted(mobile: String) {
        if (mobile.isBlank()) return
        runCatching {
            // Mark deleted for listeners that prefer flags, and also remove document.
            val data = hashMapOf<String, Any>(
                "mobile" to mobile,
                "deleted" to true,
                "isApproved" to false,
                "updatedAt" to SyncManager.now()
            )
            val ref = FirebaseFirestore.getInstance().collection("users").document(mobile)
            ref.set(data, SetOptions.merge())
            ref.delete()
        }
    }

    fun stop() {
        registrations.forEach { runCatching { it.remove() } }
        registrations.clear()
        started = false
    }
}
