package nl.timvanbergen.agenda

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Toont een melding die op de app tikt. */
object Notify {
    fun show(context: Context, channel: String, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        AgendaSync.createChannels(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_agenda)
            .setColor(context.getColor(R.color.brand))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(if (channel == AgendaSync.CHANNEL_REMINDERS) Notification.CATEGORY_REMINDER else Notification.CATEGORY_EVENT)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}

/** Gaat af op het ingeplande moment van een herinnering. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title").orEmpty().ifEmpty { "Afspraak" }
        val location = intent.getStringExtra("location").orEmpty()
        val time = AgendaSync.timeLabel(intent.getLongExtra("start", 0L), intent.getBooleanExtra("allDay", false))
        Notify.show(
            context,
            AgendaSync.CHANNEL_REMINDERS,
            id = (intent.data?.toString() ?: title).hashCode(),
            title = title,
            text = if (location.isNotEmpty()) "$time · $location" else time
        )
    }
}

/** Verversen op de achtergrond, en na een herstart of update alles opnieuw inplannen. */
class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AgendaSync.scheduleBackground(context)
        }
        val pending = goAsync()
        Thread {
            try {
                AgendaSync.run(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
