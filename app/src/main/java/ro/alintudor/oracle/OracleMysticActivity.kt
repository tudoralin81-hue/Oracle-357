package ro.alintudor.oracle

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import ro.alintudor.oracle.core.OracleBootstrap
import ro.alintudor.oracle.core.OracleLoaderQuotes
import ro.alintudor.oracle.core.OracleLocalProcessor
import ro.alintudor.oracle.core.OracleRepository
import ro.alintudor.oracle.core.snapshot
import ro.alintudor.oracle.nativeui.*
import kotlin.math.*

/** New Start experience. Module/data logic intentionally mirrors the stable activity. */
class OracleMysticActivity : Activity() {
    companion object {
        // Survives Activity recreation (e.g. rotation) as long as the app process
        // stays alive, so the boot loader only shows on a genuine fresh launch —
        // not every time the user returns from the background.
        @Volatile private var bootLoaderShownThisProcess = false
    }
    private lateinit var root: FrameLayout
    private lateinit var repository: OracleRepository
    private var currentModule: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val titles = linkedMapOf("portfolio" to "PORTFOLIO", "alerts" to "ALERTS", "news" to "NEWS", "growth" to "GROWTH", "knowledge" to "KNOWLEDGE", "analysis" to "ANALYSIS", "watchlist" to "WATCHLIST", "journal" to "ACTIVITY JOURNAL")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = OracleRepository(this)
        window.statusBarColor = Color.rgb(3, 4, 12)
        window.navigationBarColor = Color.rgb(3, 4, 12)
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(3, 4, 12)) }
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        runCatching {
            OracleBootstrap.ensure(repository)
            if (bootLoaderShownThisProcess) { showHub(); consumePendingModuleIntent() } else { bootLoaderShownThisProcess = true; showBootLoader() }
        }.onFailure { showFatalError("Oracle failed to start", it) }
        // GROWTH warm-up starts at Android app launch, not when the user opens GROWTH.
        Thread { runCatching { OracleLocalProcessor.refreshGrowthOnly(repository) } }.start()
    }

    /**
     * Boot loader shown for ~5s between app open and the START hub, visually
     * matching the GROWTH loading card (spinning Oracle icon + percentage bar
     * + rotating investor quotes). GROWTH data is already loading in the
     * background during this time.
     */
    private fun showBootLoader() {
        root.removeAllViews()
        val bg = Color.rgb(3, 4, 12)
        val muted = Color.rgb(165, 174, 195)
        val cyan = Color.rgb(75, 225, 255)
        val gold = Color.rgb(255, 205, 55)
        val green = Color.rgb(105, 245, 35)

        // Full-screen card: the whole background IS the card now.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(36), dp(36), dp(36))
            setBackgroundColor(bg)
        }

        val spinner = ImageView(this).apply {
            setImageResource(R.drawable.ic_oracle)
            contentDescription = "Oracle is starting up"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            android.animation.ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
                duration = 1100L
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
            }.start()
        }
        card.addView(spinner, LinearLayout.LayoutParams(dp(100), dp(100)).apply { gravity = Gravity.CENTER })
        card.addView(TextView(this).apply {
            text = "ORACLE"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(green); setPadding(0, dp(18), 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = "Getting things ready…"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(muted); setPadding(0, 0, 0, dp(26))
        })

        val percentLabel = TextView(this).apply {
            text = "0%"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(cyan); setPadding(0, 0, 0, dp(10))
        }
        card.addView(percentLabel)
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; isIndeterminate = false
        }
        card.addView(progressBar, LinearLayout.LayoutParams(dp(280), dp(10)))

        // Same rotating investor quotes as the GROWTH loader (OracleLoaderQuotes),
        // just cycling faster since the boot loader only runs for 5s total.
        // Bigger and colored, as requested — the quotation marks are part of the
        // quote strings themselves (OracleLoaderQuotes).
        val quoteLabel = TextView(this).apply {
            text = OracleLoaderQuotes.ALL.random()
            textSize = 21f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(gold); setPadding(dp(12), 0, dp(12), 0)
            setLineSpacing(0f, 1.2f)
            maxLines = 4
        }
        // Fixed height (not wrap_content): a 1-line quote and a 4-line quote
        // both reserve the same space, so the spinner/percent/bar above never
        // shift position as the rotating quotes change length.
        card.addView(quoteLabel, LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(24) })

        root.addView(card, FrameLayout.LayoutParams(-1, -1))

        val bootDurationMs = 5_000L
        android.animation.ValueAnimator.ofInt(0, 100).apply {
            duration = bootDurationMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Int
                progressBar.progress = v
                percentLabel.text = "$v%"
            }
        }.start()

        var lastQuote: String = quoteLabel.text.toString()
        val quoteRunnable = object : Runnable {
            override fun run() {
                var next = OracleLoaderQuotes.ALL.random()
                if (OracleLoaderQuotes.ALL.size > 1) {
                    while (next == lastQuote) next = OracleLoaderQuotes.ALL.random()
                }
                lastQuote = next
                quoteLabel.text = next
                mainHandler.postDelayed(this, 1_800L)
            }
        }
        mainHandler.postDelayed(quoteRunnable, 1_800L)

        mainHandler.postDelayed({
            mainHandler.removeCallbacks(quoteRunnable)
            if (!isFinishing) { showHub(); consumePendingModuleIntent() }
        }, bootDurationMs)
    }

    private fun showHub() {
        currentModule = null
        root.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(3, 4, 12)) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(40), dp(8), dp(20))
        }
        val hero = OracleMysticStartView(this) { openModule(it) }
        val heroHeight = (resources.displayMetrics.heightPixels * 0.86f).toInt().coerceAtLeast(dp(660))
        page.addView(hero, LinearLayout.LayoutParams(-1, heroHeight))
        scroll.addView(page)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::root.isInitialized) consumePendingModuleIntent()
    }

    /** Handles the widget's "open Growth directly" tap. Consumed once so
     *  rotating the screen or returning to the app later doesn't re-trigger it. */
    private fun consumePendingModuleIntent() {
        val module = intent?.getStringExtra("open_module") ?: return
        intent.removeExtra("open_module")
        openModule(module)
    }

    private fun openModule(key: String) {
        currentModule = key
        if (key == "alerts" && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 357)
        }
        runCatching { renderModule(key) }.onFailure { showModuleError(key, it) }

        // GROWTH is a live, independent module. The real launcher is
        // OracleMysticActivity, so Growth must be calculated here rather than
        // relying on the dead MainActivity path or the general refresh chain.
        if (key == "growth") {
            Thread {
                val result = runCatching { OracleLocalProcessor.refreshGrowthOnly(repository) }
                mainHandler.post {
                    if (currentModule != "growth" || isFinishing) return@post
                    result.onSuccess {
                        runCatching { renderModule("growth") }
                            .onFailure { showModuleError("growth", it) }
                    }.onFailure { error ->
                        showGrowthCalculationError(error)
                    }
                }
            }.start()
            return
        }

        if (key == "analysis") return
        Thread {
            val result = runCatching { OracleLocalProcessor.refresh(repository) }
            mainHandler.post {
                if (currentModule != key || isFinishing) return@post
                result.onSuccess { runCatching { renderModule(key) }.onFailure { showModuleError(key, it) } }
                    .onFailure { Toast.makeText(this, "Local refresh failed: ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun renderModule(key: String) {
        root.removeAllViews()
        val host = OracleNativeModule(this, titles[key] ?: key.uppercase(), { showHub() }, { openModule(key) })
        root.addView(host.root, FrameLayout.LayoutParams(-1, -1))
        val data = repository.snapshot()
        when (key) {
            "portfolio" -> OraclePortfolioModule(host).render(data.positions)
            "alerts" -> OracleAlertsModule(host).render(data.alerts)
            "news" -> OracleNewsModule(host).render(data.news)
            "journal" -> OracleJournalModule(host).render(data.journal, data.history, data.alerts)
            "growth", "analysis", "watchlist", "knowledge" -> OracleSimpleModule(
                host,
                titles[key] ?: key.uppercase(),
                onWatchlistTickerClick = { ticker -> openWatchlistTicker(ticker) }
            ).render(actions = data.actions, knowledge = data.knowledge, positions = data.positions, history = data.history)
        }
    }

    private fun openWatchlistTicker(ticker: String) {
        val normalized = ticker.trim().uppercase(java.util.Locale.US)
        if (normalized.isBlank()) return
        OracleSimpleModule.setTickerDraft(normalized)
        openModule("analysis")
    }

    private fun handleBack() {
        if (currentModule != null) showHub() else finish()
    }

    private fun showGrowthCalculationError(error: Throwable) {
        root.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(Color.rgb(3, 5, 12))
        }
        box.addView(TextView(this).apply {
            text = "GROWTH"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        box.addView(TextView(this).apply {
            text = "Growth calculation did not finish.\n\n${error.message ?: error.javaClass.simpleName}"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(18), 0, dp(18))
        })
        box.addView(Button(this).apply {
            text = "RETRY"
            setOnClickListener { openModule("growth") }
        })
        box.addView(Button(this).apply {
            text = "BACK TO ORACLE"
            setOnClickListener { showHub() }
        })
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showModuleError(key: String, error: Throwable) {
        root.removeAllViews()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)); setBackgroundColor(Color.rgb(3, 5, 12)) }
        box.addView(TextView(this).apply { text = "ORACLE  •  ${titles[key] ?: key.uppercase()}"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) })
        box.addView(TextView(this).apply { text = "The module could not be loaded.\n\n${error.message ?: error.javaClass.simpleName}"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY); setPadding(0, dp(24), 0, dp(24)) })
        box.addView(Button(this).apply { text = "RETRY"; setOnClickListener { openModule(key) } })
        box.addView(Button(this).apply { text = "BACK TO ORACLE"; setOnClickListener { showHub() } })
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showFatalError(title: String, error: Throwable) {
        root.removeAllViews()
        root.addView(TextView(this).apply { text = "$title\n\n${error.message ?: error.javaClass.simpleName}"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(dp(32), dp(32), dp(32), dp(32)) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() { handleBack() }
}
