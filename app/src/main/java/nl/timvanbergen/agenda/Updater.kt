package nl.timvanbergen.agenda

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Automatische updates: kijkt op GitHub of er een nieuwere release van de app is
 * (tag v1.0.N, waarbij N het versienummer is), en installeert die na een tik op "Bijwerken".
 */
object Updater {

    private const val LATEST = "https://api.github.com/repos/timmmmmmmmn/agenda-android/releases/latest"
    private const val PREFS = "agenda-update"
    private const val CHECK_EVERY = 6 * 60 * 60 * 1000L

    private data class Release(val code: Long, val name: String, val notes: String, val url: String)

    @Volatile private var busy = false
    private var waitingForPermission: Release? = null

    fun currentCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun currentName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** Bij het openen van de app: hooguit eens per zes uur kijken. */
    fun checkQuietly(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("checked", 0L) < CHECK_EVERY) return
        check(activity, manual = false)
    }

    /** Vanuit het "Meer"-menu: altijd kijken, en laten weten als alles al bijgewerkt is. */
    fun check(activity: Activity, manual: Boolean) {
        if (busy) return
        busy = true
        Thread {
            val release = try {
                fetchLatest()
            } catch (_: Exception) {
                null
            }
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("checked", System.currentTimeMillis()).apply()
            activity.runOnUiThread {
                busy = false
                if (activity.isFinishing) return@runOnUiThread
                when {
                    release == null -> if (manual) toast(activity, "Kon niet controleren op updates")
                    release.code > currentCode(activity) -> offer(activity, release)
                    manual -> toast(activity, "Je hebt de nieuwste versie (${currentName(activity)})")
                }
            }
        }.start()
    }

    /** Terug in de app na het geven van toestemming om te installeren: verder waar we waren. */
    fun resume(activity: Activity) {
        val release = waitingForPermission ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) return
        waitingForPermission = null
        download(activity, release)
    }

    private fun fetchLatest(): Release? {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "AgendaApp")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(code, tag.removePrefix("v"), json.optString("body").trim(), a.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun offer(activity: Activity, release: Release) {
        val notes = release.notes.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Nieuwe versie ${release.name}")
            .setMessage(if (notes.isNotEmpty()) "Wat is er nieuw: $notes" else "Er staat een nieuwe versie van Agenda klaar.")
            .setPositiveButton("Bijwerken") { _, _ -> start(activity, release) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun start(activity: Activity, release: Release) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // Eenmalig: Android vraagt of Agenda apps mag installeren (alleen voor zijn eigen updates).
            waitingForPermission = release
            AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Toestemming nodig")
                .setMessage("Zet in het volgende scherm \"Toestaan van deze bron\" aan, zodat Agenda zichzelf kan bijwerken. Daarna gaat de update vanzelf verder.")
                .setPositiveButton("Naar instellingen") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
                .setNegativeButton("Annuleren") { _, _ -> waitingForPermission = null }
                .show()
            return
        }
        download(activity, release)
    }

    private fun download(activity: Activity, release: Release) {
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Bijwerken naar ${release.name}")
            .setMessage("Downloaden…")
            .setView(ProgressBar(activity).apply { isIndeterminate = true; setPadding(0, 32, 0, 32) })
            .setCancelable(false)
            .show()
        Thread {
            val file = File(activity.cacheDir, "agenda-update.apk")
            val ok = try {
                val conn = URL(release.url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "AgendaApp")
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                install(activity, file)
                true
            } catch (_: Exception) {
                false
            }
            activity.runOnUiThread {
                dialog.dismiss()
                if (!ok) toast(activity, "Downloaden mislukt, probeer het later opnieuw")
            }
        }.start()
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Vanaf Android 12 kunnen volgende updates zonder extra bevestiging (als Agenda zelf de vorige installeerde).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("agenda.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

/** Uitslag van de installatie; Android kan nog om een bevestiging vragen. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, "Bijwerken mislukt${if (msg.isNotEmpty()) ": $msg" else ""}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
