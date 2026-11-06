package io.github.dmulder.glashutte.privileged

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.dmulder.glashutte.R

/**
 * The notification Glashutte posts while pairing is needed, with a `RemoteInput` text field so
 * the user can type the 6-digit code straight from the notification shade — over the still-
 * open Wireless Debugging pair dialog, which keeps the mDNS pairing service alive long
 * enough for libadb to look it up.
 *
 * This is the trick Shizuku uses, and it sidesteps the catch-22 of "to enter the code into
 * the app, you have to leave the dialog, and the moment you do the mDNS service is gone".
 */
object PairingNotifier {

    /** Channel id — created lazily on first post. */
    private const val CHANNEL_ID = "glashutte_pairing"

    /** Notification id; one at a time. */
    private const val NOTIFICATION_ID = 1

    /** RemoteInput bundle key for the typed code; read by [PairingInputReceiver]. */
    const val KEY_PAIRING_CODE = "pairing_code"

    /** Broadcast action [PairingInputReceiver] listens for. */
    const val ACTION_SUBMIT_CODE = "io.github.dmulder.glashutte.privileged.SUBMIT_PAIRING_CODE"

    /** Post the pairing notification. No-op if POST_NOTIFICATIONS is not granted. */
    fun showPairingPrompt(context: Context) {
        ensureChannel(context)
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel(context.getString(R.string.privilege_pairing_hint))
            .build()
        val replyIntent = Intent(ACTION_SUBMIT_CODE)
            .setPackage(context.packageName)
            .setClass(context, PairingInputReceiver::class.java)
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(
            R.drawable.ic_keyboard,
            context.getString(R.string.privilege_action_pair),
            replyPendingIntent,
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.privilege_notification_title))
            .setContentText(context.getString(R.string.privilege_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.privilege_notification_text)),
            )
            .addAction(action)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** Take the notification down — call when state moves out of NEEDS_PAIRING. */
    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.privilege_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.privilege_notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
