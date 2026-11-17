package com.albayda.court.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class User(
    val username: String,
    val mobile: String,
    val passwordHash: String,
    val isApproved: Boolean,
    val isAdmin: Boolean,
    val createdAt: String = ""
)

data class UserLog(
    val time: String,
    val username: String,
    val mobile: String,
    val action: String,
    val details: String = ""
)

object AuthManager {
    private const val PREF_NAME = "auth_secure_prefs"
    private lateinit var prefs: android.content.SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context, PREF_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            prefs = context.getSharedPreferences(PREF_NAME + "_fallback", Context.MODE_PRIVATE)
        }

        val users = getUsers().toMutableList()
        if (users.isEmpty()) {
            users.add(
                User(
                    username = "احمد محسون",
                    mobile = "771421919",
                    passwordHash = hashPassword("734840710"),
                    isApproved = true,
                    isAdmin = true,
                    createdAt = now()
                )
            )
            saveUsers(users)
        } else {
            val idx = users.indexOfFirst { it.mobile == "771421919" || it.username == "احمد محسون" }
            if (idx >= 0) {
                val u = users[idx]
                if (!u.isAdmin || !u.isApproved) {
                    users[idx] = u.copy(isAdmin = true, isApproved = true)
                    saveUsers(users)
                }
            }
        }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getUsers(): List<User> {
        val json = prefs.getString("users_list", "[]") ?: "[]"
        return try { gson.fromJson(json, object : TypeToken<List<User>>() {}.type) } catch (_: Exception) { emptyList() }
    }

    private fun saveUsers(users: List<User>) {
        prefs.edit().putString("users_list", gson.toJson(users)).apply()
    }

    fun register(username: String, mobile: String, password: String): Boolean {
        val users = getUsers().toMutableList()
        if (users.any { it.mobile == mobile || it.username == username }) return false
        val u = User(username, mobile, hashPassword(password), false, false, now())
        users.add(u)
        saveUsers(users)
        addLog(UserLog(now(), username, mobile, "تسجيل جديد", "بانتظار الموافقة"))
        return true
    }

    /** Adds/updates a user received from the sync backend so every device shares the same registry. */
    fun upsertRemoteUser(username: String, mobile: String, passwordHash: String, createdAt: String) {
        if (mobile.isBlank()) return
        val users = getUsers().toMutableList()
        val index = users.indexOfFirst { it.mobile == mobile }
        if (index >= 0) {
            users[index] = users[index].copy(
                username = username.ifBlank { users[index].username },
                passwordHash = passwordHash.ifBlank { users[index].passwordHash },
                createdAt = createdAt.ifBlank { users[index].createdAt }
            )
        } else {
            users.add(User(username, mobile, passwordHash, false, false, createdAt))
        }
        saveUsers(users)
    }

    fun setApproved(mobile: String, approved: Boolean) {
        val users = getUsers().toMutableList()
        val index = users.indexOfFirst { it.mobile == mobile }
        if (index >= 0) {
            users[index] = users[index].copy(isApproved = approved)
            saveUsers(users)
        }
    }

    fun findByMobile(mobile: String): User? = getUsers().firstOrNull { it.mobile == mobile }

    fun login(identifier: String, password: String): User? {
        val hashed = hashPassword(password)
        val user = getUsers().find {
            (it.username == identifier || it.mobile == identifier) && it.passwordHash == hashed
        }
        if (user != null) addLog(UserLog(now(), user.username, user.mobile, "تسجيل دخول", if (user.isApproved) "ناجح" else "غير معتمد"))
        return user
    }

    fun approveUser(mobile: String, approve: Boolean) {
        val users = getUsers().toMutableList()
        val index = users.indexOfFirst { it.mobile == mobile }
        if (index != -1) {
            users[index] = users[index].copy(isApproved = approve)
            saveUsers(users)
            val u = users[index]
            addLog(UserLog(now(), u.username, u.mobile, if (approve) "اعتماد مستخدم" else "رفض/تعطيل مستخدم", ""))
        }
    }

    fun rejectUser(mobile: String) {
        val users = getUsers().toMutableList()
        val u = users.find { it.mobile == mobile }
        if (u != null) {
            users.removeAll { it.mobile == mobile }
            saveUsers(users)
            addLog(UserLog(now(), u.username, u.mobile, "رفض مستخدم", "تم حذف الطلب"))
        }
    }

    fun isPrimaryAdmin(user: User?): Boolean {
        if (user == null) return false
        return user.isAdmin && (user.mobile == "771421919" || user.username == "احمد محسون")
    }

    fun saveSession(user: User) {
        prefs.edit().putString("session_user", gson.toJson(user)).putBoolean("biometric_enabled", true).apply()
    }

    fun getSession(): User? {
        val json = prefs.getString("session_user", null) ?: return null
        return try { gson.fromJson(json, User::class.java) } catch (_: Exception) { null }
    }

    fun clearSession() {
        prefs.edit().remove("session_user").apply()
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean("biometric_enabled", false)

    fun getLogs(): List<UserLog> {
        val json = prefs.getString("user_logs", "[]") ?: "[]"
        return try { gson.fromJson(json, object : TypeToken<List<UserLog>>() {}.type) } catch (_: Exception) { emptyList() }
    }

    fun addLog(log: UserLog) {
        val logs = getLogs().toMutableList()
        logs.add(0, log)
        val trimmed = if (logs.size > 500) logs.take(500) else logs
        prefs.edit().putString("user_logs", gson.toJson(trimmed)).apply()
    }

    fun pendingUsers(): List<User> = getUsers().filter { !it.isApproved && !it.isAdmin }
    fun allNonAdminUsers(): List<User> = getUsers().filter { !it.isAdmin }
}
