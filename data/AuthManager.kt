package com.albayda.court.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest

data class User(val username: String, val mobile: String, val passwordHash: String, val isApproved: Boolean, val isAdmin: Boolean)

object AuthManager {
    private const val PREF_NAME = "auth_secure_prefs"
    private lateinit var prefs: android.content.SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        prefs = EncryptedSharedPreferences.create(context, PREF_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        
        // تهيئة مدير النظام الافتراضي إذا لم يكن موجوداً
        if (getUsers().isEmpty()) {
            val admin = User("احمد محسون", "771421919", hashPassword("734840710"), true, true)
            saveUsers(listOf(admin))
        }
    }

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getUsers(): List<User> {
        val json = prefs.getString("users_list", "[]") ?: "[]"
        val type = object : TypeToken<List<User>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveUsers(users: List<User>) {
        prefs.edit().putString("users_list", gson.toJson(users)).apply()
    }

    fun register(username: String, mobile: String, password: String): Boolean {
        val users = getUsers().toMutableList()
        if (users.any { it.mobile == mobile || it.username == username }) return false
        
        val newUser = User(username, mobile, hashPassword(password), isApproved = false, isAdmin = false)
        users.add(newUser)
        saveUsers(users)
        return true
    }

    fun login(identifier: String, password: String): User? {
        val hashed = hashPassword(password)
        return getUsers().find { (it.username == identifier || it.mobile == identifier) && it.passwordHash == hashed }
    }

    fun approveUser(mobile: String, approve: Boolean) {
        val users = getUsers().toMutableList()
        val index = users.indexOfFirst { it.mobile == mobile }
        if (index != -1) {
            users[index] = users[index].copy(isApproved = approve)
            saveUsers(users)
        }
    }

    fun saveSession(user: User, context: Context) {
        prefs.edit().putString("session_user", gson.toJson(user)).apply()
    }

    fun getSession(): User? {
        val json = prefs.getString("session_user", null) ?: return null
        return gson.fromJson(json, User::class.java)
    }
}