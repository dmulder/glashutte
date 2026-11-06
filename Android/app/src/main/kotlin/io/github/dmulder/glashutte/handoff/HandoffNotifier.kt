package io.github.dmulder.glashutte.handoff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.dmulder.glashutte.MainActivity
import io.github.dmulder.glashutte.R

/**
 * The always-reachable way back: an ongoing notification with a "Return to workspace" action
 * for as long as an app runs flat. It matters most in phone-mirror mode, where the phone screen
 * shows the app and Glashutte's own screen is out of sight.
 */
object HandoffNotifier {
    const val ACTION_RETURN = "io.github.dmulder.glashutte.handoff.RETURN_TO_WORKSPACE"
    private const val CHANNEL_ID = "glashutte_handoff"
    private const val NOTIFICATION_ID = 0x4846 // "HF"

    fun show(context: Context, appLabel: String, modeLabel: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Flat app on the glasses", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while an app runs flat on the glasses; tap to return to the workspace"
                    setShowBadge(false)
                },
            )
        }
        val back = Intent(context, MainActivity::class.java)
            .setAction(ACTION_RETURN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, back, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val action = Notification.Action.Builder(null, "Return to workspace", pending).build()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$appLabel is running flat")
            .setContentText(modeLabel)
            .setOngoing(true)
            .setContentIntent(pending)
            .addAction(action)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}
