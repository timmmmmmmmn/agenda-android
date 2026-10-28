package nl.timvanbergen.agenda

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Haalt bij de server op welke herinneringen eraan komen en wat anderen in gedeelde
 * agenda's hebben veranderd, plant de herinneringen in en toont de wijzigingen als melding.
 * Draait als de app opent, na elke wijziging in de app, en ongeveer elk kwartier op de achtergrond.
 */
object AgendaSync {

    private const val FEED = "https://agenda.timvanbergen.nl/api/feed"
    private const val PREFS = "agenda-sync"
    const val CHANNEL_REMINDERS = "herinneringen"
    const val CHANNEL_CHANGES = "wijzigingen"

    private val lock = Any()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Herinneringen", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Herinneringen voor je afspraken"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CHANGES, "Wijzigingen door anderen", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Als iemand iets toevoegt, wijzigt of verwijdert in een gedeelde agenda"
            }
        )
    }

    /** Ongeveer elk kwartier op de achtergrond verversen (Android mag dit bundelen om batterij te sparen). */
    fun scheduleBackground(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            syncIntent(context)
        )
    }

    private fun syncIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, SyncReceiver::class.java).setAction("nl.timvanbergen.agenda.SYNC"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Blokkeert (netwerk); altijd vanaf een achtergrondthread aanroepen. */
    fun run(context: Context) = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cookie = CookieManager.getInstance().getCookie(FEED) ?: return
        val since = prefs.getLong("since", 0L)

        val feed = try {
            fetch("$FEED?since=${if (since > 0) since else System.currentTimeMillis()}", cookie)
        } catch (_: Exception) {
            return
        } ?: return

        createChannels(context)
        scheduleReminders(context, feed)
        if (since > 0) notifyChanges(context, feed)
        prefs.edit().putLong("since", feed.optLong("now", System.currentTimeMillis())).apply()
    }

    private fun fetch(url: String, cookie: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Herinneringen ----------

    private fun scheduleReminders(context: Context, feed: JSONObject) {
        val am = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet("scheduled", emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val current = mutableSetOf<String>()
        val list = feed.optJSONArray("reminders")

        for (i in 0 until (list?.length() ?: 0)) {
            val r = list!!.getJSONObject(i)
            val at = r.getLong("at")
            if (at <= now) continue
            val key = r.getString("key")
            current += key
            val intent = reminderIntent(context, key)
                .putExtra("title", r.optString("title"))
                .putExtra("start", r.optLong("start"))
                .putExtra("allDay", r.optBoolean("allDay"))
                .putExtra("location", r.optString("location"))
            val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }

        // Herinneringen van afspraken die zijn verwijderd of verzet weer intrekken.
        for (key in previous - current) {
            PendingIntent.getBroadcast(context, 0, reminderIntent(context, key), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
                ?.let { am.cancel(it); it.cancel() }
        }
        prefs.edit().putStringSet("scheduled", current).apply()
    }

    private fun reminderIntent(context: Context, key: String) =
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse("agenda://herinnering/$key"))

    // ---------- Wijzigingen door anderen ----------

    private fun notifyChanges(context: Context, feed: JSONObject) {
        val list = feed.optJSONArray("changes") ?: return
        for (i in 0 until list.length()) {
            val c = list.getJSONObject(i)
            val who = c.optString("who")
            val action = c.optString("action")
            val title = c.optString("title")
            val calendar = c.optString("calendar")
            Notify.show(
                context,
                CHANNEL_CHANGES,
                id = "${c.optLong("at")}-$title".hashCode(),
                title = "$who heeft een afspraak $action",
                text = if (calendar.isNotEmpty()) "$title · $calendar" else title
            )
        }
    }

    fun timeLabel(start: Long, allDay: Boolean): String {
        if (allDay) return "Hele dag"
        val time = SimpleDateFormat("HH:mm", Locale.forLanguageTag("nl-NL")).format(Date(start))
        return "Om $time"
    }
}
