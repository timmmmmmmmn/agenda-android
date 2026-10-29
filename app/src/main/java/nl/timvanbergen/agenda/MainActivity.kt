package nl.timvanbergen.agenda

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * De agenda als app: de webinterface van agenda.timvanbergen.nl in een eigen venster.
 * Alles wat op de website verandert, zit daardoor meteen ook in de app. De pagina
 * herkent de app aan "AgendaApp/" in de user agent en schakelt dan de app-stand in
 * (zwevende navigatie, vervagende randen, panelen van onderen).
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var bars: WindowInsetsControllerCompat

    // Ruimte van status- en navigatiebalk in CSS-pixels; de pagina leest dit via AgendaNative.insets().
    @Volatile private var insetTop = 0f
    @Volatile private var insetBottom = 0f

    // Bestand kiezen voor <input type="file"> (importeren van een .ics).
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

    // Toestemming voor meldingen (Android 13+); daarna meteen herinneringen ophalen.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { syncInBackground() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tekenen tot onder status- en navigatiebalk; de pagina vult die ruimte zelf (met vervaging).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
        }

        val root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        bars = WindowCompat.getInsetsController(window, root)
        setDarkBars(night)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val density = resources.displayMetrics.density
            // Toetsenbord open: de WebView krimpt, en onderin is dan geen navigatiebalk meer te zien.
            view.setPadding(sys.left, 0, sys.right, ime.bottom)
            insetTop = sys.top / density
            insetBottom = if (ime.bottom > 0) 0f else sys.bottom / density
            pushInsets()
            WindowInsetsCompat.CONSUMED
        }

        CookieManager.getInstance().setAcceptCookie(true)
        web.setBackgroundColor(getColor(R.color.page_bg))
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isHapticFeedbackEnabled = true
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            userAgentString = "$userAgentString AgendaApp/1.4"
        }
        web.addJavascriptInterface(Bridge(), "AgendaNative")
        web.webViewClient = AgendaClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    pickFile.launch(params.createIntent().apply { type = "*/*" })
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
        // Back-up downloaden: via Downloadbeheer, met de inlogcookie erbij.
        web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            try {
                val name = URLUtil.guessFileName(url, disposition, mime)
                val request = DownloadManager.Request(Uri.parse(url))
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .addRequestHeader("User-Agent", userAgent)
                    .setMimeType(mime)
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                getSystemService(DownloadManager::class.java).enqueue(request)
                Toast.makeText(this, "Downloaden naar Downloads…", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Downloaden lukte niet", Toast.LENGTH_LONG).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Eerst de webinterface laten sluiten wat open staat (venster, paneel, zoeken).
                web.evaluateJavascript("window.agendaBack ? agendaBack() : false") { handled ->
                    if (handled == "true") return@evaluateJavascript
                    if (web.canGoBack()) web.goBack() else finish()
                }
            }
        })

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(HOME + linkQuery(intent))
        }

        AgendaSync.createChannels(this)
        AgendaSync.scheduleBackground(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Updater.checkQuietly(this)
    }

    /** Vanuit de widget: een afspraak openen of een nieuwe maken. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.data ?: return
        val js = when (data.host) {
            "open" -> data.pathSegments.takeIf { it.size == 2 }?.let { "window.agendaOpen && agendaOpen('${it[0].toLongOrNull() ?: return}','${it[1].toLongOrNull() ?: return}')" }
            "nieuw" -> "window.agendaNew && agendaNew()"
            else -> null
        } ?: return
        if (onOurSite()) web.evaluateJavascript(js, null) else web.loadUrl(HOME + linkQuery(intent))
    }

    private fun linkQuery(intent: Intent?): String {
        val data = intent?.data ?: return ""
        return when (data.host) {
            "open" -> data.pathSegments.takeIf { it.size == 2 && it.all { s -> s.toLongOrNull() != null } }?.let { "?open=${it[0]}-${it[1]}" } ?: ""
            "nieuw" -> "?nieuw=1"
            else -> ""
        }
    }

    override fun onResume() {
        super.onResume()
        syncInBackground()
        Updater.resume(this)
    }

    private fun syncInBackground() {
        val app = applicationContext
        Thread {
            AgendaSync.run(app)
            AgendaWidget.refresh(app)
        }.start()
    }

    private fun setDarkBars(dark: Boolean) {
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
    }

    private fun pushInsets() {
        web.evaluateJavascript(
            "(function(r){r.setProperty('--sat','${insetTop}px');r.setProperty('--sab','${insetBottom}px');})(document.documentElement.style)",
            null
        )
    }

    private fun onOurSite(): Boolean = web.url?.let { Uri.parse(it).host == HOST } ?: false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** Wat de pagina van de app mag vragen: trillen, statusbalk-kleur, en de ruimte van de systeembalken. */
    private inner class Bridge {

        @JavascriptInterface
        fun insets(): String = "$insetTop,$insetBottom"

        @JavascriptInterface
        fun haptic(kind: String) {
            runOnUiThread {
                if (!onOurSite()) return@runOnUiThread
                val effect = when {
                    kind == "confirm" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
                    kind == "confirm" -> HapticFeedbackConstants.VIRTUAL_KEY
                    else -> HapticFeedbackConstants.CLOCK_TICK
                }
                web.performHapticFeedback(effect)
            }
        }

        @JavascriptInterface
        fun appVersion(): String = Updater.currentName(this@MainActivity)

        /** "App bijwerken" in het Meer-menu. */
        @JavascriptInterface
        fun checkForUpdate() {
            runOnUiThread { if (onOurSite()) Updater.check(this@MainActivity, manual = true) }
        }

        /** Na opslaan of verwijderen in de app: herinneringen meteen opnieuw inplannen. */
        @JavascriptInterface
        fun syncReminders() {
            runOnUiThread { if (onOurSite()) syncInBackground() }
        }

        @JavascriptInterface
        fun setDark(dark: Boolean) {
            runOnUiThread { if (onOurSite()) setDarkBars(dark) }
        }
    }

    private inner class AgendaClient : WebViewClient() {

        // Links naar andere sites openen in de browser, de agenda zelf blijft in de app.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == HOST || url.scheme == "file") return false
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url))
            } catch (_: ActivityNotFoundException) {
            }
            return true
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            pushInsets()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            pushInsets()
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) view.loadUrl(OFFLINE)
        }
    }

    private companion object {
        const val HOST = "agenda.timvanbergen.nl"
        const val HOME = "https://$HOST/"
        const val OFFLINE = "file:///android_asset/offline.html"
    }
}
