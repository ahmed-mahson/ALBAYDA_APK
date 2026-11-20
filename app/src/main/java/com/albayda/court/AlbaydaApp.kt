package com.albayda.court

import android.app.Application
import com.albayda.court.data.AuthManager
import com.albayda.court.data.CaseRecordsRepository
import com.albayda.court.data.FirebaseSync
import com.albayda.court.data.SyncManager

class AlbaydaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Core app services
        try { AuthManager.init(this) } catch (_: Exception) {}
        try { SyncManager.init(this) } catch (_: Exception) {}
        try { CaseRecordsRepository.init(this) } catch (_: Exception) {}
        // Firebase real-time sync, offline cache, and push notification preparation
        try { FirebaseSync.init(this) } catch (_: Exception) {}
    }
}
