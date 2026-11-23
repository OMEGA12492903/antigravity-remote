package com.omegax.antigravityremote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {
    const val CHANNEL_ALERTS = "ag_agent_alerts"
    const val CHANNEL_KEEPALIVE = "ag_keepalive"
    const val KEEPALIVE_NOTIFICATION_ID = 1001
    const val EXTRA_WEB_NOTIFICATION_TAG = "extra_web_notification_tag"

    private val alertIdCounter = AtomicInteger(2000)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alerts_desc)
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }

        val keepAliveChannel = NotificationChannel(
            CHANNEL_KEEPALIVE,
            context.getString(R.string.channel_keepalive_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_keepalive_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
        }

        nm.createNotificationChannel(alertsChannel)
        nm.createNotificationChannel(keepAliveChannel)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showAgentAlert(
        context: Context,
        title: String,
        body: String,
        tag: String? = null
    ): Int {
        ensureChannels(context)
        val notificationId = tag?.hashCode()?.let { 3000 + (it and 0x7FFF) }
            ?: alertIdCounter.incrementAndGet()

        if (!hasNotificationPermission(context)) {
            return notificationId
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!tag.isNullOrBlank()) {
                putExtra(EXTRA_WEB_NOTIFICATION_TAG, tag)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val safeTitle = SecurityPolicy.sanitizePromptText(title, maxLength = 140)
            .ifBlank { "Antigravity Remote" }
        val safeBody = SecurityPolicy.sanitizePromptText(body, maxLength = 600)
            .ifBlank { "Tu agente requiere atención o ha terminado." }

        // Redacted lock-screen version so private code/prompts aren't exposed when locked
        val publicNotification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Antigravity Remote")
            .setContentText("Actividad del agente — desbloquea para ver")
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(safeTitle)
            .setContentText(safeBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission revoked concurrently
        }
        return notificationId
    }

    fun cancelNotification(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }
}
