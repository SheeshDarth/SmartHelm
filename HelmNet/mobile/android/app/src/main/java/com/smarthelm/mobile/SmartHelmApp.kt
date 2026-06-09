package com.smarthelm.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.smarthelm.mobile.service.DetectionService

class SmartHelmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Silent channel for the persistent foreground service notification
        nm.createNotificationChannel(
            NotificationChannel(
                DetectionService.CHANNEL_DETECTION,
                "Detection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification while drowsiness detection is active"
            }
        )

        // High-importance channel for drowsiness alert heads-up notifications
        val alarmAudioAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(
                DetectionService.CHANNEL_ALERTS,
                "Drowsiness Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fires when rider drowsiness is detected"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    alarmAudioAttr
                )
            }
        )
    }
}
