package com.liveTranslation.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.liveTranslation.MainActivity
import com.liveTranslation.R

/**
 * Creates and manages the persistent notification required by foreground services.
 */
object NotificationHelper {

    const val CHANNEL_ID   = "live_translation_channel"
    const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live Translation",
            NotificationManager.IMPORTANCE_LOW   // Low = no sound, still shows in status bar
        ).apply {
            description = "Shown while audio capture is active"
            setShowBadge(false)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun buildNotification(context: Context): Notification {
        // Tap notification → open MainActivity
        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop-service action inside notification
        val stopIntent = PendingIntent.getService(
            context, 0,
            Intent(context, com.liveTranslation.service.AudioCaptureService::class.java)
                .setAction(com.liveTranslation.service.AudioCaptureService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Live Translation Active")
            .setContentText("Capturing and translating audio…")
            .setSmallIcon(R.drawable.ic_translate)          // add a vector drawable with this name
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
