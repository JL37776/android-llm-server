package de.cyclenerd.android.llm.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.cyclenerd.android.llm.server.R
import de.cyclenerd.android.llm.server.ui.MainActivity

/**
 * Helper for creating and managing foreground service notifications.
 *
 * Foreground services MUST display a persistent notification to inform
 * users that something is running in the background. This is an Android
 * requirement, not optional.
 *
 * Why persistent notification?
 * - User transparency: Shows what's using battery/resources
 * - User control: Provides stop action without opening app
 * - Android requirement: Foreground service without notification gets killed
 *
 * Notification importance:
 * We use IMPORTANCE_DEFAULT so that aggressive OEM power managers
 * (HyperOS, MIUI, ColorOS) treat the foreground service as important
 * enough to survive screen-off and Doze.
 */
object NotificationHelper {
    private const val CHANNEL_ID = "llm_server_channel"
    private const val NOTIFICATION_ID = 1001

    const val ACTION_STOP_SERVICE = "de.cyclenerd.android.llm.server.ACTION_STOP"

    /**
     * Creates the notification channel for the service.
     *
     * Required on Android O (API 26)+. Channels let users control
     * notification behavior per category (sound, vibration, priority).
     *
     * Why IMPORTANCE_DEFAULT?
     * OEM skins (HyperOS, MIUI, ColorOS) may kill foreground services
     * whose notification channel is IMPORTANCE_LOW during screen-off.
     * IMPORTANCE_DEFAULT keeps the service alive without being intrusive.
     *
     * @param context Application context
     */
    fun createNotificationChannel(context: Context) {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "LLM Server",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Shows when the local LLM inference server is running"
                setShowBadge(false) // Don't show app icon badge
            }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Creates the persistent notification for the foreground service.
     *
     * The notification displays:
     * - Current server status (Starting, Running, Error)
     * - IP addresses where server is accessible
     * - Stop action button
     *
     * Why ongoing?
     * Setting ongoing=true prevents users from swiping away the notification.
     * This is important because dismissing the notification would make the
     * service invisible to the user while still using resources.
     *
     * Why DEFAULT priority?
     * Matches the IMPORTANCE_DEFAULT channel so OEM power managers
     * keep the foreground service alive during screen-off.
     *
     * Stop action:
     * Users can stop the server directly from the notification without
     * opening the app. This broadcasts an intent that the service handles.
     *
     * @param context Application context
     * @param state Current service state
     * @return Notification instance
     */
    fun createNotification(
        context: Context,
        state: ServiceState,
    ): Notification {
        // Tap notification to open app
        val openAppIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val openAppPendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // Build base notification
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Local LLM Server")
                .setContentText(state.toDisplayText())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(openAppPendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Add server info for Running state
        if (state is ServiceState.Running && state.ipAddresses.isNotEmpty()) {
            val infoText =
                buildString {
                    appendLine("Server URLs:")
                    state.ipAddresses.forEach { ip ->
                        appendLine("http://$ip:${state.port}")
                    }
                    appendLine()
                    appendLine("Model: ${state.modelName}")
                    if (state.uptime > 0) {
                        appendLine("Uptime: ${formatUptime(state.uptime)}")
                    }
                }

            builder.setStyle(
                NotificationCompat
                    .BigTextStyle()
                    .bigText(infoText),
            )

            // Add stop action
            builder.addAction(createStopAction(context))
        }

        return builder.build()
    }

    /**
     * Creates the stop action for the notification.
     *
     * When user taps this action, a broadcast is sent to the service
     * to initiate graceful shutdown.
     *
     * Why broadcast?
     * We can't call service methods directly from notification actions.
     * Broadcasts are the standard Android way to communicate from
     * notifications to services.
     *
     * @param context Application context
     * @return NotificationCompat.Action
     */
    private fun createStopAction(context: Context): NotificationCompat.Action {
        val stopIntent =
            Intent(ACTION_STOP_SERVICE).apply {
                setPackage(context.packageName)
            }
        val stopPendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        return NotificationCompat.Action
            .Builder(
                0, // No icon
                "Stop Server",
                stopPendingIntent,
            ).build()
    }

    /**
     * Formats uptime in human-readable form.
     *
     * @param seconds Uptime in seconds
     * @return Formatted string (e.g., "2h 34m", "45m", "12s")
     */
    private fun formatUptime(seconds: Long): String =
        when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }

    /**
     * Gets the notification ID for the service.
     *
     * @return Notification ID
     */
    fun getNotificationId(): Int = NOTIFICATION_ID
}
