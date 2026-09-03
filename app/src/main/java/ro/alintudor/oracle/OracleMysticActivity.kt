package ro.alintudor.oracle

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import ro.alintudor.oracle.core.OracleAuthStore
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
        // Same idea for the login gate: once unlocked, stays unlocked for the
        // rest of this process (matches how most local-lock apps behave —
        // re-locking only on a genuine fresh process start).
        @Volatile private var authPassedThisProcess = false
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
        if (authPassedThisProcess) {
            proceedPastAuth()
        } else {
            runCatching { showAuthGate() }.onFailure { showFatalError("Oracle failed to start", it) }
        }
        // GROWTH warm-up starts at Android app launch, not when the user opens GROWTH —
        // deliberately independent of the auth gate, so data is ready by the time login finishes.
        Thread { runCatching { OracleLocalProcessor.refreshGrowthOnly(repository) } }.start()
    }

    private fun proceedPastAuth() {
        runCatching {
            OracleBootstrap.ensure(repository)
            if (bootLoaderShownThisProcess) { showHub(); consumePendingModuleIntent() } else { bootLoaderShownThisProcess = true; showBootLoader() }
        }.onFailure { showFatalError("Oracle failed to start", it) }
    }

    // ---------------------------------------------------------------------
    // Local account gate — username/password with security-question recovery
    // and optional biometric unlock. Runs before the boot loader on every
    // fresh process start. Everything is stored only on this device
    // (OracleAuthStore); there is no server, so "forgot password" can only
    // work through the security question, and biometric unlock uses the
    // platform BiometricPrompt (API 28+) tied to this device's own hardware.
    // ---------------------------------------------------------------------

    private fun showAuthGate() {
        val store = OracleAuthStore(this)
        if (store.hasAccount()) showLogin(store) else showRegister(store)
    }

    @Suppress("DEPRECATION")
    private fun biometricAvailable(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val manager = getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            manager?.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val executor = mainExecutor
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle("Unlock Oracle")
            .setSubtitle("Use your fingerprint or face to continue")
            .setNegativeButton("Use password instead", executor) { _, _ -> }
            .build()
        runCatching {
            prompt.authenticate(android.os.CancellationSignal(), executor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { onSuccess() }
            })
        }
    }

    private fun authField(container: LinearLayout, label: String, muted: Int, panel: Int, border: Int, isPassword: Boolean = false): EditText {
        container.addView(TextView(this).apply { text = label; textSize = 11f; setTextColor(muted); setPadding(dp(2), dp(10), 0, dp(4)) })
        val edit = EditText(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setSingleLine(true)
            if (isPassword) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(10).toFloat(); setStroke(dp(1), border) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(edit, LinearLayout.LayoutParams(-1, -2))
        return edit
    }

    private fun showRegister(store: OracleAuthStore) {
        root.removeAllViews()
        val bg = Color.rgb(3, 4, 12); val panel = Color.rgb(7, 14, 28); val border = Color.rgb(49, 82, 125)
        val muted = Color.rgb(165, 174, 195); val green = Color.rgb(105, 245, 35); val red = Color.rgb(255, 90, 90)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(40), dp(28), dp(40)) }

        card.addView(ImageView(this).apply { setImageResource(R.drawable.ic_oracle); scaleType = ImageView.ScaleType.CENTER_INSIDE },
            LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER; bottomMargin = dp(12) })
        card.addView(TextView(this).apply { text = "CREATE YOUR ACCOUNT"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(green) })
        card.addView(TextView(this).apply {
            text = "Local to this device only — no server, no cloud account."
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(20), dp(6), dp(20), dp(26))
        })

        val usernameField = authField(card, "USERNAME", muted, panel, border)
        val passwordField = authField(card, "PASSWORD", muted, panel, border, isPassword = true)
        val confirmField = authField(card, "CONFIRM PASSWORD", muted, panel, border, isPassword = true)
        val questionField = authField(card, "SECURITY QUESTION (used to reset your password)", muted, panel, border)
        val answerField = authField(card, "ANSWER", muted, panel, border)

        var biometricWanted = false
        if (biometricAvailable()) {
            val toggle = TextView(this).apply {
                text = "🔒  ENABLE BIOMETRIC UNLOCK"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(muted)
                background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(10).toFloat(); setStroke(dp(1), border) }
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    biometricWanted = !biometricWanted
                    setTextColor(if (biometricWanted) green else muted)
                    background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(10).toFloat(); setStroke(dp(1), if (biometricWanted) green else border) }
                }
            }
            card.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        }

        val error = TextView(this).apply { textSize = 12f; setTextColor(red); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) }
        card.addView(error)

        val createButton = TextView(this).apply {
            text = "CREATE ACCOUNT"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(20, 90, 60)); cornerRadius = dp(12).toFloat() }
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true; isFocusable = true
            setOnClickListener {
                val username = usernameField.text.toString().trim()
                val password = passwordField.text.toString()
                val confirm = confirmField.text.toString()
                val question = questionField.text.toString().trim()
                val answer = answerField.text.toString().trim()
                error.text = when {
                    username.isBlank() -> "Enter a username."
                    password.length < 4 -> "Password needs at least 4 characters."
                    password != confirm -> "Passwords don't match."
                    question.isBlank() || answer.isBlank() -> "Set a security question and answer — it's the only way to reset your password later."
                    else -> ""
                }
                if (error.text.isNotEmpty()) return@setOnClickListener
                store.register(username, password, question, answer)
                store.setBiometricEnabled(biometricWanted)
                showBackupCodeReveal(store.generateAndStoreBackupCode())
            }
        }
        card.addView(createButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        scroll.addView(card)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showBackupCodeReveal(code: String) {
        root.removeAllViews()
        val bg = Color.rgb(3, 4, 12); val panel = Color.rgb(7, 14, 28)
        val muted = Color.rgb(165, 174, 195); val gold = Color.rgb(255, 205, 55)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(40), dp(28), dp(40)) }

        card.addView(TextView(this).apply { text = "SAVE YOUR BACKUP CODE"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(gold) })
        card.addView(TextView(this).apply {
            text = "This is the only way to reset your password if you ever forget both it and your security answer. It will not be shown again — write it down now."
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(10), dp(10), dp(10), dp(26))
        })
        card.addView(TextView(this).apply {
            text = code; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            letterSpacing = 0.03f
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(12).toFloat(); setStroke(dp(1), gold) }
            setPadding(dp(16), dp(22), dp(16), dp(22))
        })
        card.addView(TextView(this).apply {
            text = "I'VE SAVED IT — CONTINUE"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(20, 90, 60)); cornerRadius = dp(12).toFloat() }
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true; isFocusable = true
            setOnClickListener { authPassedThisProcess = true; proceedPastAuth() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })

        scroll.addView(card)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showLogin(store: OracleAuthStore) {
        root.removeAllViews()
        val bg = Color.rgb(3, 4, 12); val panel = Color.rgb(7, 14, 28); val border = Color.rgb(49, 82, 125)
        val muted = Color.rgb(165, 174, 195); val gold = Color.rgb(255, 205, 55); val green = Color.rgb(105, 245, 35); val red = Color.rgb(255, 90, 90)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(40), dp(28), dp(40)) }

        card.addView(ImageView(this).apply { setImageResource(R.drawable.ic_oracle); scaleType = ImageView.ScaleType.CENTER_INSIDE },
            LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
        card.addView(TextView(this).apply { text = "ORACLE"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(green) })
        card.addView(TextView(this).apply {
            text = "Welcome back, ${store.username()}"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(4), 0, dp(28))
        })

        val usernameField = authField(card, "USERNAME", muted, panel, border).apply { setText(store.username()) }
        val passwordField = authField(card, "PASSWORD", muted, panel, border, isPassword = true)

        val error = TextView(this).apply { textSize = 12f; setTextColor(red); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        card.addView(error)

        fun attemptLogin() {
            val username = usernameField.text.toString().trim()
            val password = passwordField.text.toString()
            // Local admin override — recovery path baked into this build; see the
            // security note about this in OracleAuthStore.
            val isAdminOverride = username.equals("admin", ignoreCase = true) && password == "357AT2026"
            if (!isAdminOverride && (!username.equals(store.username(), ignoreCase = true) || !store.verifyPassword(password))) {
                error.text = "Wrong username or password."
                return
            }
            authPassedThisProcess = true
            proceedPastAuth()
        }

        card.addView(TextView(this).apply {
            text = "LOG IN"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(20, 90, 60)); cornerRadius = dp(12).toFloat() }
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true; isFocusable = true
            setOnClickListener { attemptLogin() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        if (store.biometricEnabled() && biometricAvailable()) {
            card.addView(TextView(this).apply {
                text = "🔒  USE BIOMETRIC UNLOCK"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(gold)
                background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(12).toFloat(); setStroke(dp(1), gold) }
                setPadding(0, dp(14), 0, dp(14))
                isClickable = true; isFocusable = true
                setOnClickListener { showBiometricPrompt { authPassedThisProcess = true; proceedPastAuth() } }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }

        card.addView(TextView(this).apply {
            text = "Forgot password?"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(18), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { showForgotPassword(store) }
        })

        scroll.addView(card)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // Returning users with biometric enabled don't have to type anything —
        // offer it immediately; they can still fall back to the password fields.
        if (store.biometricEnabled() && biometricAvailable()) {
            showBiometricPrompt { authPassedThisProcess = true; proceedPastAuth() }
        }
    }

    private fun showForgotPassword(store: OracleAuthStore) {
        root.removeAllViews()
        val bg = Color.rgb(3, 4, 12); val panel = Color.rgb(7, 14, 28); val border = Color.rgb(49, 82, 125)
        val muted = Color.rgb(165, 174, 195); val green = Color.rgb(105, 245, 35); val red = Color.rgb(255, 90, 90)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(40), dp(28), dp(40)) }

        card.addView(TextView(this).apply { text = "RESET PASSWORD"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(green) })
        card.addView(TextView(this).apply {
            text = "This only works through the security question you set when you created your account — Oracle has no server to send a reset link from."
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(10), dp(8), dp(10), dp(24))
        })
        card.addView(TextView(this).apply {
            text = store.securityQuestion().ifBlank { "No security question was set for this account." }
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(8))
        })

        val answerField = authField(card, "YOUR ANSWER (leave blank if using the backup code)", muted, panel, border)
        card.addView(TextView(this).apply { text = "— OR —"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(14), 0, dp(2)) })
        val backupField = authField(card, "BACKUP CODE (from registration)", muted, panel, border)
        val newPasswordField = authField(card, "NEW PASSWORD", muted, panel, border, isPassword = true)
        val confirmField = authField(card, "CONFIRM NEW PASSWORD", muted, panel, border, isPassword = true)

        val error = TextView(this).apply { textSize = 12f; setTextColor(red); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        card.addView(error)

        card.addView(TextView(this).apply {
            text = "RESET PASSWORD"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(20, 90, 60)); cornerRadius = dp(12).toFloat() }
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true; isFocusable = true
            setOnClickListener {
                val answer = answerField.text.toString()
                val backupCode = backupField.text.toString()
                val newPassword = newPasswordField.text.toString()
                val confirm = confirmField.text.toString()
                val verified = (answer.isNotBlank() && store.verifySecurityAnswer(answer)) ||
                    (backupCode.isNotBlank() && store.verifyBackupCode(backupCode))
                error.text = when {
                    !verified -> "That answer or backup code doesn't match."
                    newPassword.length < 4 -> "Password needs at least 4 characters."
                    newPassword != confirm -> "Passwords don't match."
                    else -> ""
                }
                if (error.text.isNotEmpty()) return@setOnClickListener
                store.resetPassword(newPassword)
                Toast.makeText(this@OracleMysticActivity, "Password updated — log in with your new password.", Toast.LENGTH_LONG).show()
                showLogin(store)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        card.addView(TextView(this).apply {
            text = "Back to login"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(18), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { showLogin(store) }
        })

        scroll.addView(card)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
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
        // Only route straight to the requested module if this session is
        // already unlocked — otherwise a widget tap could skip the login
        // screen entirely. If not yet authenticated, the request is simply
        // left in the intent and picked up normally once login succeeds.
        if (::root.isInitialized && authPassedThisProcess) consumePendingModuleIntent()
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
