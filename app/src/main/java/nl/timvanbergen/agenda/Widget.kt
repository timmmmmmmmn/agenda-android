package nl.timvanbergen.agenda

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.CookieManager
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Widget op het beginscherm: de afspraken van vandaag en de komende twee weken.
 * Tik op een afspraak om hem in de app te openen, of op + voor een nieuwe.
 */
class AgendaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context, id))
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    companion object {
        private val NL = Locale.forLanguageTag("nl-NL")

        /** Na een sync of wijziging in de app: de widgets opnieuw laten laden. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AgendaWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) manager.updateAppWidget(id, views(context, id))
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        private fun open(context: Context, uri: String, code: Int, mutable: Boolean = false): PendingIntent = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).setData(Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        )

        private fun views(context: Context, id: Int): RemoteViews {
            val today = LocalDate.now()
            return RemoteViews(context.packageName, R.layout.widget).apply {
                setTextViewText(R.id.widget_day, today.dayOfMonth.toString())
                setTextViewText(R.id.widget_date, today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", NL)).replaceFirstChar { it.uppercase() })
                @Suppress("DEPRECATION")
                setRemoteAdapter(R.id.widget_list, Intent(context, WidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = Uri.parse("agenda://widget/$id")
                })
                setEmptyView(R.id.widget_list, R.id.widget_empty)
                setOnClickPendingIntent(R.id.widget_head, open(context, "agenda://app", 10))
                setOnClickPendingIntent(R.id.widget_empty, open(context, "agenda://app", 10))
                setOnClickPendingIntent(R.id.widget_add, open(context, "agenda://nieuw", 11))
                setPendingIntentTemplate(R.id.widget_list, open(context, "agenda://app", 12, mutable = true))
            }
        }
    }
}

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = WidgetRows(applicationContext)
}

/** Rijen van de widget: een kopje per dag, met daaronder de afspraken. */
private class WidgetRows(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(val header: String? = null, val time: String = "", val title: String = "", val sub: String = "", val color: Int = 0, val open: String = "")

    private var rows: List<Row> = emptyList()
    private val nl = Locale.forLanguageTag("nl-NL")
    private val zone = ZoneId.systemDefault()

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 2
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    override fun onDataSetChanged() {
        val prefs = context.getSharedPreferences("agenda-widget", Context.MODE_PRIVATE)
        val fresh = load()
        if (fresh != null) prefs.edit().putString("events", fresh.first).putString("cals", fresh.second).apply()
        val events = prefs.getString("events", null) ?: return run { rows = emptyList() }
        val cals = prefs.getString("cals", null) ?: "[]"
        rows = build(JSONArray(events), JSONArray(cals))
    }

    private fun get(url: String, cookie: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Cookie", cookie)
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun load(): Pair<String, String>? {
        val cookie = CookieManager.getInstance().getCookie(BASE) ?: return null
        val from = LocalDate.now().atStartOfDay(zone).toInstant()
        val to = from.plusSeconds(15L * 86400)
        val q = "from=${URLEncoder.encode(from.toString(), "UTF-8")}&to=${URLEncoder.encode(to.toString(), "UTF-8")}"
        val events = get("$BASE/api/events?$q", cookie) ?: return null
        val cals = get("$BASE/api/calendars", cookie) ?: return null
        return events to cals
    }

    private fun build(events: JSONArray, cals: JSONArray): List<Row> {
        val colors = HashMap<String, Int>()
        for (i in 0 until cals.length()) {
            val c = cals.getJSONObject(i)
            colors[c.getString("id")] = try { Color.parseColor(c.getString("color")) } catch (_: Exception) { Color.GRAY }
        }
        data class Ev(val start: Instant, val end: Instant, val allDay: Boolean, val startDay: LocalDate, val endDay: LocalDate, val title: String, val location: String, val cal: String, val open: String)
        val list = ArrayList<Ev>()
        for (i in 0 until events.length()) {
            val o = events.getJSONObject(i)
            if (!colors.containsKey(o.getString("calendar_id"))) continue
            val allDay = o.optBoolean("allDay")
            val s = o.getString("start")
            val e = o.getString("end")
            val startDay = if (allDay) LocalDate.parse(s) else Instant.parse(s).atZone(zone).toLocalDate()
            val endDay = if (allDay) LocalDate.parse(e).minusDays(1) else Instant.parse(e).minusMillis(1).atZone(zone).toLocalDate()
            list.add(Ev(
                if (allDay) startDay.atStartOfDay(zone).toInstant() else Instant.parse(s),
                if (allDay) LocalDate.parse(e).atStartOfDay(zone).toInstant() else Instant.parse(e),
                allDay, startDay, endDay,
                o.optString("title").ifEmpty { "(geen titel)" }, o.optString("location"),
                o.getString("calendar_id"), "agenda://open/${o.getLong("id")}/${o.optLong("key")}"
            ))
        }
        val now = Instant.now()
        val today = LocalDate.now()
        val hm = DateTimeFormatter.ofPattern("H:mm", nl)
        val out = ArrayList<Row>()
        for (d in 0 until 15) {
            val day = today.plusDays(d.toLong())
            val items = list.filter { !day.isBefore(it.startDay) && !day.isAfter(it.endDay) && it.end.isAfter(if (d == 0) now else day.atStartOfDay(zone).toInstant()) }
                .sortedWith(compareBy({ !it.allDay }, { it.start }))
            if (items.isEmpty()) continue
            val label = when (d) {
                0 -> "Vandaag"
                1 -> "Morgen"
                else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", nl)).replaceFirstChar { it.uppercase() }
            }
            out.add(Row(header = label))
            for (ev in items) {
                val time = when {
                    ev.allDay -> "Hele dag"
                    ev.startDay == day -> ev.start.atZone(zone).format(hm)
                    else -> "…"
                }
                val until = if (!ev.allDay && ev.endDay == day) "tot ${ev.end.atZone(zone).format(hm)}" else ""
                out.add(Row(time = time, title = ev.title, sub = listOf(until, ev.location).filter { it.isNotEmpty() }.joinToString(" · "), color = colors[ev.cal] ?: Color.GRAY, open = ev.open))
            }
            if (out.size > 60) break
        }
        return out
    }

    override fun getViewAt(position: Int): RemoteViews {
        val r = rows.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_day)
        if (r.header != null) {
            return RemoteViews(context.packageName, R.layout.widget_day).apply { setTextViewText(R.id.widget_row_day, r.header) }
        }
        return RemoteViews(context.packageName, R.layout.widget_item).apply {
            setTextViewText(R.id.widget_row_time, r.time)
            setTextViewText(R.id.widget_row_title, r.title)
            setTextViewText(R.id.widget_row_sub, r.sub)
            setViewVisibility(R.id.widget_row_sub, if (r.sub.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            setInt(R.id.widget_row_bar, "setColorFilter", r.color)
            setOnClickFillInIntent(R.id.widget_row, Intent().setData(Uri.parse(r.open)))
        }
    }

    private companion object {
        const val BASE = "https://agenda.timvanbergen.nl"
    }
}
