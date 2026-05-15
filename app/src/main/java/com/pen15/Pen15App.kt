package com.pen15

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.pen15.data.storage.StorageManager
import com.pen15.domain.connection.ConnectionService
import com.pen15.domain.engagement.EngagementRepository

class Pen15App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        StorageManager.init(this)
        EngagementRepository.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                ConnectionService.CHANNEL_ID,
                getString(R.string.connection_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.connection_notification_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var instance: Pen15App
            private set
    }
}
