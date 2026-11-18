package com.albayda.court

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.albayda.court.data.AuthManager
import com.albayda.court.data.CaseRecordsRepository
import com.albayda.court.data.SyncManager

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        try {
            ContextCompat.startForegroundService(this, Intent(this, SyncService::class.java))
        } catch (_: Exception) {
            // Some restricted devices block foreground services; in-app sync still works.
        }
        AuthManager.init(this)
        SyncManager.init(this)
        CaseRecordsRepository.init(this)
        Handler(Looper.getMainLooper()).postDelayed({
            val session = AuthManager.getSession()
            if (session != null && session.isApproved) {
                startActivity(Intent(this, MainActivity::class.java))
            } else {
                startActivity(Intent(this, LoginActivity::class.java))
            }
            finish()
        }, 1200)
    }
}
