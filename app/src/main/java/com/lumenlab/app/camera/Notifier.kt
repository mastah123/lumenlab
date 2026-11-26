package com.lumenlab.app.camera

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lumenlab.app.MainActivity

/** Whether the app is on screen. A finish notification is only useful when it is not. */
object AppState {
    @Volatile
    var foreground = false
}

/** The "session finished" notification. */
object Notifier {
    private const val CHANNEL = "session_done"
    private const val MIN_RUN_MS = 60_000L

    /** False on Android 13 and later until the person allows notifications. */
    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Tells the person a long session is done, only when they turned it on, left the app and the run took over a minute. */
    fun finished(context: Context, enabled: Boolean, startedAtElapsedMs: Long, title: String, text: String) {
        if (!enabled || AppState.foreground) return
        if (SystemClock.elapsedRealtime() - startedAtElapsedMs < MIN_RUN_MS) return
        if (!permitted(context)) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Session finished", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(1001, n)
        } catch (e: SecurityException) {
            CrashLog.record(context, e)
        }
    }
}
