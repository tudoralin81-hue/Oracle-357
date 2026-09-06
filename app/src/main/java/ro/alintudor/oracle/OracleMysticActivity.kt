package ro.alintudor.oracle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import ro.alintudor.oracle.core.OracleAccountMailer
import ro.alintudor.oracle.core.OracleApiClient
import ro.alintudor.oracle.core.OracleAuthStore
import ro.alintudor.oracle.core.OracleBootstrap
import ro.alintudor.oracle.core.OracleFirebaseMessagingService
import ro.alintudor.oracle.core.OracleKnowledgeSync
import ro.alintudor.oracle.core.OracleLoaderQuotes
import ro.alintudor.oracle.core.OracleLocalProcessor
import ro.alintudor.oracle.core.OracleRepository
import ro.alintudor.oracle.core.OracleSyncManager
import ro.alintudor.oracle.core.snapshot
import ro.alintudor.oracle.nativeui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/** New Start experience. Module/data logic intentionally mirrors the stable activity. */
class OracleMysticActivity : Activity() {
    companion object {
        // Same idea for the login gate: once unlocked, stays unlocked for the
        // rest of this process (matches how most local-lock apps behave —
        // re-locking only on a genuine fresh process start).
        @Volatile private var authPassedThisProcess = false
        private const val EMERGENCY_IMPORT_REQUEST = 4243
    }
    private lateinit var root: FrameLayout
    private lateinit var repository: OracleRepository
    private var currentModule: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val titles = linkedMapOf("portfolio" to "PORTFOLIO", "alerts" to "ALERTS", "news" to "NEWS", "growth" to "GROWTH", "knowledge" to "KNOWLEDGE", "analysis" to "ANALYSIS", "watchlist" to "WATCHLIST", "journal" to "ACTIVITY JOURNAL")

    private val termsText = """
        By creating an account you agree to the following:

        1. What this app is
        Lux Oculi is a personal project, developed and operated by a single independent developer — not a company, and not affiliated with any financial institution, broker, or other software product of a similar name. Your account, and the data described below, are stored on a server operated by the developer (alintudor.ro), not on your device alone.

        2. No warranty
        Lux Oculi is provided "as is," without warranty of any kind. Market data, calculations, scores, and recommendations may be inaccurate, delayed, or incomplete. The developer makes no guarantee of accuracy, availability, or fitness for any particular purpose, and the service may be modified, interrupted, or discontinued at any time.

        3. Your account and data
        Creating an account stores your username, a hashed (not plaintext) password, hashed answers to your chosen security questions, and a hashed backup recovery code, on the developer's server. If you provide a notification email, it is used only to send you account and alert notifications you've asked for. Your Portfolio, Watchlist, Alerts, and Activity Journal entries are also stored there so they sync across app opens. This data is never sold, and is never shared with third parties except the minimum needed to operate the service itself (e.g. Google Firebase, solely to deliver push notifications to your device).
        You're responsible for keeping your password, security answers, and backup code safe — they're how you (or the developer) can recover or verify your account.
        You may ask the developer to delete your account and its stored data at any time; see the contact information the developer has provided you.

        4. Approval and access
        New accounts require approval by the developer before use. Access may be suspended or revoked at the developer's discretion, including for suspected misuse, without prior notice.

        5. Trademarks and naming
        "Lux Oculi" and its logo are the developer's own branding for this app. Any resemblance to the name, branding, or products of other companies is unintentional, and this app claims no affiliation, sponsorship, or endorsement by any such company. Company and ticker names, logos, and data shown within the app (e.g. stock symbols, index names) belong to their respective owners and are used solely for identification and informational purposes.

        6. No liability
        The developer is not liable for any loss, financial or otherwise, arising from your use of this app, including decisions made based on information it displays, or from any interruption, error, or loss of data in the service.

        7. Changes
        These terms may be updated at any time, as this is a personal project under active development. Continued use of the app after a change constitutes acceptance of the updated terms.

        See also: Disclaimer, for important information about the investment-related content in this app.
    """.trimIndent()

    private val disclaimerText = """
        Lux Oculi is not a financial advisor, broker, or licensed investment service. Nothing shown in this app, including Growth scores, signals (BUY / HOLD / SELL), risk ratings, forecasted potential, Portfolio decisions, or Alerts, constitutes financial, investment, tax, or legal advice.

        All figures are generated by automated calculations based on market data that may be delayed, incomplete, or inaccurate. Market data is sourced from third-party providers the developer does not control. Past performance and any forecasted percentage are not guarantees of future results.

        Investing involves risk, including the possible loss of principal. Before making any investment decision, consult a licensed financial advisor who can consider your personal circumstances.

        The developer of this app assumes no responsibility for any financial loss resulting from its use.
    """.trimIndent()

    /** Scrollable, newest-first view of the engine log. */
    private fun showGrowthLogDialog() {
        val lines = ro.alintudor.oracle.core.OracleGrowthLog.read(this, 400)
        val panel = Color.rgb(7, 14, 28)
        val scroll = ScrollView(this).apply { setBackgroundColor(panel) }
        scroll.addView(TextView(this).apply {
            text = if (lines.isEmpty()) "Nothing recorded yet.\n\nOpen Growth to trigger a run, or wait for the nightly background scan."
                   else lines.asReversed().joinToString("\n")
            textSize = 10.5f; typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(200, 208, 222)); setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
        })
        android.app.AlertDialog.Builder(this)
            .setTitle("Growth engine log (last ${lines.size})")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Download") { _, _ ->
                val path = ro.alintudor.oracle.core.OracleGrowthLog.export(this)
                Toast.makeText(this, if (path != null) "Growth log saved to Downloads." else "The log is empty.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    /** Metadata-only — endpoint, timestamp, HTTP outcome, byte sizes — NEVER
     *  a value from the request or response body (see OracleNetworkLog).
     *  Text is deliberately non-selectable: nothing here is sensitive on its
     *  own, but this stays "look, don't take" the way it was asked for. */
    private fun showNetworkLogDialog() {
        val lines = ro.alintudor.oracle.core.OracleNetworkLog.read(300)
        val panel = Color.rgb(7, 14, 28)
        val scroll = ScrollView(this).apply { setBackgroundColor(panel) }
        scroll.addView(TextView(this).apply {
            text = if (lines.isEmpty()) "Nothing recorded yet.\n\nEvery call the app makes to the server will show up here."
                   else lines.asReversed().joinToString("\n")
            textSize = 10.5f; typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(200, 208, 222)); setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(false)
        })
        android.app.AlertDialog.Builder(this)
            .setTitle("Server communication (last ${lines.size})")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun offerBiometricEnrollIfNeeded(store: OracleAuthStore, onDone: () -> Unit) {
        if (store.biometricEnabled() || store.biometricOffered() || !biometricAvailable() || isFinishing || isDestroyed) {
            ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "biometric-enroll: skipped (enabled=${store.biometricEnabled()}, offered=${store.biometricOffered()}, available=${biometricAvailable()}, finishing=$isFinishing, destroyed=$isDestroyed)")
            onDone(); return
        }
        store.setBiometricOffered(true)
        ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "biometric-enroll: showing dialog")
        // A failed dialog show (window not ready, activity mid-transition)
        // must never stall the login flow — skip straight to onDone().
        runCatching {
            android.app.AlertDialog.Builder(this)
                .setTitle("Enable fingerprint unlock?")
                .setMessage("Skip typing your password next time you open Lux Oculi — unlock with your fingerprint instead.")
                .setPositiveButton("Enable") { _, _ -> store.setBiometricEnabled(true); ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "biometric-enroll: user enabled"); onDone() }
                .setNegativeButton("Not now") { _, _ -> ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "biometric-enroll: user declined"); onDone() }
                .setCancelable(false)
                .show()
        }.onFailure { ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "biometric-enroll: dialog.show() threw: ${it.javaClass.simpleName}: ${it.message}"); onDone() }
    }

    private fun legalDialog(title: String, body: String) {
        val panel = Color.rgb(7, 14, 28)
        val muted = Color.rgb(200, 206, 220)
        val scroll = ScrollView(this).apply { setBackgroundColor(panel) }
        scroll.addView(TextView(this).apply {
            text = body; textSize = 13f; setTextColor(muted); setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(22), dp(18), dp(22), dp(18))
        })
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showTermsDialog() = legalDialog("Terms & Conditions", termsText)
    private fun showDisclaimerDialog() = legalDialog("Disclaimer", disclaimerText)

    /** The background alarm (OracleDailyLogoutReceiver) only clears the
     *  stored session — it has no way to redirect a screen that's already
     *  open. This catches that case: if 15:00 has passed today and the
     *  forced logout hasn't run yet, apply it now and drop straight to
     *  login, same as a revoked-account 401 does. */
    override fun onResume() {
        super.onResume()
        runCatching {
            if (ro.alintudor.oracle.core.OracleDailyLogoutReceiver.applyIfDue(this)) {
                currentModule = null
                showLogin(OracleAuthStore(this))
                Toast.makeText(this, "Signed out for the day's 3pm security refresh. Log back in to continue.", Toast.LENGTH_LONG).show()
            }
        }
    }

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
            runCatching { showAuthGate() }.onFailure { showFatalError("Lux Oculi failed to start", it) }
        }
        // GROWTH warm-up starts at Android app launch, not when the user opens GROWTH —
        // deliberately independent of the auth gate, so data is ready by the time login finishes.
        Thread { runCatching { OracleLocalProcessor.refreshGrowthOnly(repository) } }.start()
    }

    // Guards against proceedPastAuth() firing twice for one login (e.g. the
    // auto-triggered biometric prompt plus a manual tap landing close
    // together) - two overlapping calls would each start their own 5s boot
    // loader timer, and whichever fires first cuts the other's countdown
    // short from the user's perspective. Reset per Activity instance, not
    // per process, so a genuine future login still gets its own loader.
    private var proceedingPastAuth = false

    private fun proceedPastAuth() {
        ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "proceedPastAuth: entered (already proceeding=$proceedingPastAuth)")
        if (proceedingPastAuth) return
        proceedingPastAuth = true
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "proceedPastAuth: requesting POST_NOTIFICATIONS")
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 357) }
                .onFailure { ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "proceedPastAuth: requestPermissions threw: ${it.javaClass.simpleName}: ${it.message}") }
        }
        runCatching {
            OracleBootstrap.ensure(repository)
            OracleKnowledgeSync.scheduleNextCheck(this)
            ro.alintudor.oracle.core.OracleAlertCheckReceiver.schedule(this)
            ro.alintudor.oracle.core.OracleGrowthScanReceiver.schedule(this)
            ro.alintudor.oracle.core.OracleDailyLogoutReceiver.schedule(this)
            // If today's full-universe scan hasn't run yet (fresh install, or
            // the phone was off overnight), start it now in the background.
            ro.alintudor.oracle.core.OracleGrowthScanReceiver.scanNowIfMissing(this)
            // Re-check widget authorization right now, independent of whether
            // Growth actually recomputes anything below. The HARD FREEZE
            // (OracleLocalProcessor) reuses an already-valid snapshot for
            // today without ever calling updateAll() itself — so going
            // Demo -> real login (or any other re-auth) in the same process,
            // with a same-day snapshot already frozen from before, would
            // otherwise leave the widget stuck showing whatever the PRIOR
            // session's authorization state was (e.g. still blank from Demo).
            ro.alintudor.oracle.widget.OracleGrowthWidgetProvider.updateAll(this)
            ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "proceedPastAuth: showing boot loader")
            showBootLoader()
        }.onFailure { proceedingPastAuth = false; ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "proceedPastAuth: FAILED: ${it.javaClass.simpleName}: ${it.message}"); showFatalError("Lux Oculi failed to start", it) }
    }

    // ---------------------------------------------------------------------
    // Account gate — server-backed login/register/recovery on alintudor.ro
    // (OracleApiClient), with a local session token (OracleAuthStore) and
    // optional biometric unlock as a shortcut to reusing that token without
    // retyping the password. Runs before the boot loader on every fresh
    // process start.
    // ---------------------------------------------------------------------

    private fun showAuthGate() {
        // Accounts live on the server now — always start at Login (with a
        // path to Register), the way most server-backed apps work, rather
        // than deciding locally whether an account "exists".
        showLogin(OracleAuthStore(this))
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
            .setTitle("Unlock Lux Oculi")
            .setSubtitle("Use your fingerprint or face to continue")
            .setNegativeButton("Use password instead", executor) { _, _ -> }
            .build()
        runCatching {
            prompt.authenticate(android.os.CancellationSignal(), executor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { onSuccess() }
            })
        }
    }

    /** Mirrors the server's oracle_password_requirement_error() exactly —
     *  gives the same feedback immediately, without waiting on a round-trip
     *  to find out the password will be rejected. Returns null if valid. */
    private fun passwordRequirementError(password: String): String? {
        if (password.length < 8 || password.length > 16) return "Password must be 8\u201316 characters."
        if (!password.any { it.isUpperCase() }) return "Password needs at least one uppercase letter."
        if (!password.any { it.isLowerCase() }) return "Password needs at least one lowercase letter."
        if (!password.any { it.isDigit() }) return "Password needs at least one number."
        if (password.all { it.isLetterOrDigit() }) return "Password needs at least one symbol."
        return null
    }

    private fun authField(container: LinearLayout, label: String, muted: Int, panel: Int, border: Int, isPassword: Boolean = false, onAutofilled: ((View) -> Unit)? = null): EditText {
        container.addView(TextView(this).apply { text = label; textSize = 11f; setTextColor(muted); setPadding(dp(2), dp(10), 0, dp(4)) })
        // Plain EditText, unless a caller wants to know about genuine system
        // autofill specifically (as opposed to manual typing) — overriding
        // View.autofill() is the one hook Android fires only for that.
        val edit = if (onAutofilled != null) {
            object : EditText(this) {
                override fun autofill(value: android.view.autofill.AutofillValue) {
                    super.autofill(value)
                    onAutofilled(this)
                }
            }
        } else EditText(this)
        edit.apply {
            setTextColor(Color.WHITE); textSize = 15f; setSingleLine(true)
            if (isPassword) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(10).toFloat(); setStroke(dp(1), border) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (onAutofilled != null) {
                // Fallback for autofill services that don't go through the
                // standard View.autofill() hook (Samsung Pass has been known
                // not to, on some OS versions) — a jump of more than one
                // character in a single change is never manual keystroke
                // typing, so it's a reliable stand-in signal either way.
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (count > 1) onAutofilled(this@apply) }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
        }
        container.addView(edit, LinearLayout.LayoutParams(-1, -2))
        return edit
    }

    private fun hideKeyboard(view: View) {
        // Posted with a short delay: if fired in the exact same instant as
        // the autofill event, the system's own focus handling for that
        // event can immediately re-show the keyboard right after, undoing
        // an immediate call. Clearing focus too, not just hiding, is the
        // more forceful combination that actually sticks.
        view.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }, 100L)
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
            text = "Your account is created on alintudor.ro — log back into it any time, on this device or after a reinstall."
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(20), dp(6), dp(20), dp(26))
        })

        val usernameField = authField(card, "USERNAME", muted, panel, border)
        val passwordField = authField(card, "PASSWORD", muted, panel, border, isPassword = true)
        card.addView(TextView(this).apply {
            text = "8\u201316 characters, with an uppercase letter, a lowercase letter, a number, and a symbol. Case-sensitive."
            textSize = 10.5f; setTextColor(muted); setPadding(dp(2), dp(4), dp(2), 0)
        })
        val confirmField = authField(card, "CONFIRM PASSWORD", muted, panel, border, isPassword = true)

        card.addView(TextView(this).apply {
            text = "SECURITY QUESTIONS — answer any ${OracleAuthStore.REQUIRED_SECURITY_ANSWERS} of the ${OracleAuthStore.SECURITY_QUESTIONS.size} below"
            textSize = 11f; setTextColor(muted); setPadding(dp(2), dp(18), 0, dp(4))
        })
        val questionAnswerFields = OracleAuthStore.SECURITY_QUESTIONS.mapIndexed { index, question ->
            index to authField(card, question, muted, panel, border)
        }.toMap()

        val notifyEmailField = authField(card, "NOTIFICATION EMAIL (optional — get an email when this account is created)", muted, panel, border)

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

        val termsCheckbox = CheckBox(this).apply {
            text = "I accept the Terms & Conditions"
            textSize = 12f; setTextColor(muted)
            buttonTintList = android.content.res.ColorStateList.valueOf(green)
        }
        val termsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, 0) }
        termsRow.addView(termsCheckbox, LinearLayout.LayoutParams(0, -2, 1f))
        termsRow.addView(TextView(this).apply {
            text = "View"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(75, 225, 255))
            isClickable = true; isFocusable = true
            setOnClickListener { showTermsDialog() }
        })
        card.addView(termsRow)

        val error = TextView(this).apply { textSize = 12f; setTextColor(red); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) }
        card.addView(error)

        val createButton = TextView(this).apply {
            text = "CREATE ACCOUNT"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(20, 90, 60)); cornerRadius = dp(12).toFloat() }
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true; isFocusable = true
        }
        createButton.setOnClickListener {
            val username = usernameField.text.toString().trim()
            val password = passwordField.text.toString()
            val confirm = confirmField.text.toString()
            val answeredCount = questionAnswerFields.values.count { it.text.toString().isNotBlank() }
            error.text = when {
                username.isBlank() -> "Enter a username."
                passwordRequirementError(password) != null -> passwordRequirementError(password)!!
                password != confirm -> "Passwords don't match."
                answeredCount < OracleAuthStore.REQUIRED_SECURITY_ANSWERS -> "Answer at least ${OracleAuthStore.REQUIRED_SECURITY_ANSWERS} security questions — they're the only way to reset your password later."
                !termsCheckbox.isChecked -> "You need to accept the Terms & Conditions to continue."
                else -> ""
            }
            if (error.text.isNotEmpty()) return@setOnClickListener
            createButton.isEnabled = false; createButton.text = "CREATING ACCOUNT…"
            val answers = questionAnswerFields.mapValues { entry -> entry.value.text.toString() }.filterValues { it.isNotBlank() }
            val notifyEmail = notifyEmailField.text.toString().trim()
            Thread {
                val result = OracleApiClient.register(username, password, answers, notifyEmail)
                runOnUiThread {
                    result.onSuccess { pair ->
                        val (token, backupCode) = pair
                        if (token.isBlank()) {
                            // Server-side approval flow: the account exists but the
                            // owner has to approve it before the first login.
                            android.app.AlertDialog.Builder(this@OracleMysticActivity)
                                .setTitle("Account created \u2014 awaiting approval")
                                .setMessage("Thanks, $username. New accounts are approved by hand. You'll be able to log in once it's approved${if (notifyEmail.isNotBlank()) " — we'll email $notifyEmail" else ""}.\n\nYour backup code: $backupCode\nKeep it — it's the only way to reset your password.")
                                .setPositiveButton("OK") { _, _ -> showLogin(store) }
                                .setCancelable(false).show()
                            return@onSuccess
                        }
                        store.saveSession(username, token)
                        store.setBiometricEnabled(biometricWanted)
                        store.setBiometricOffered(true)
                        OracleFirebaseMessagingService.registerCurrentToken(this@OracleMysticActivity)
                        if (notifyEmail.isNotBlank()) {
                            store.setNotificationEmail(notifyEmail)
                            OracleAccountMailer.open(this@OracleMysticActivity, notifyEmail, username, token)
                        }
                        showBackupCodeReveal(backupCode)
                    }.onFailure {
                        createButton.isEnabled = true; createButton.text = "CREATE ACCOUNT"
                        error.text = "Couldn't create the account: ${it.message}"
                    }
                }
            }.start()
        }
        card.addView(createButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        card.addView(TextView(this).apply {
            text = "Already have an account? Log in"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(18), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { showLogin(store) }
        })

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
            text = "This is the only way to reset your password if you ever forget both it and your security answers. It will not be shown again — write it down now."
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(10), dp(10), dp(10), dp(26))
        })
        card.addView(TextView(this).apply {
            text = code; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            letterSpacing = 0.03f
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(12).toFloat(); setStroke(dp(1), gold) }
            setPadding(dp(16), dp(22), dp(16), dp(22))
        })
        card.addView(TextView(this).apply {
            text = "\uD83D\uDCCB  COPY CODE"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(gold)
            setPadding(0, dp(10), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Lux Oculi backup code", code))
                Toast.makeText(this@OracleMysticActivity, "Copied.", Toast.LENGTH_SHORT).show()
            }
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
        // Every route back to this screen (exit-demo, LOG OUT, a session that
        // expired) must be able to complete a fresh login afterward. This is
        // the one choke point all of them share, so resetting the guard here
        // covers all of them, not just the specific path being exercised today.
        proceedingPastAuth = false
        root.removeAllViews()
        val bg = Color.rgb(3, 4, 12); val panel = Color.rgb(7, 14, 28); val border = Color.rgb(49, 82, 125)
        val muted = Color.rgb(165, 174, 195); val gold = Color.rgb(255, 205, 55); val green = Color.rgb(105, 245, 35); val red = Color.rgb(255, 90, 90)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(40), dp(28), dp(40)) }

        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_oracle); scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pulseX = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f, 1f)
            val pulseY = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f, 1f)
            android.animation.ObjectAnimator.ofPropertyValuesHolder(this, pulseX, pulseY).apply {
                duration = 2200L
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }.start()
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER; bottomMargin = dp(10) })
        card.addView(TextView(this).apply { text = "LUX OCULI"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(green) })
        card.addView(TextView(this).apply {
            text = "SEE MORE. KNOW FIRST."; textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            letterSpacing = 0.16f; setTextColor(muted); setPadding(0, dp(3), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (store.username().isNotBlank()) "Welcome back, ${store.username()}" else "Log in to your account"
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(10), 0, dp(28))
        })

        val usernameField = authField(card, "USERNAME", muted, panel, border).apply { setText(store.username()) }
        var afterPasswordAutofill: ((View) -> Unit)? = null
        val passwordField = authField(card, "PASSWORD", muted, panel, border, isPassword = true, onAutofilled = { view -> afterPasswordAutofill?.invoke(view) })

        val error = TextView(this).apply { textSize = 12f; setTextColor(red); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        card.addView(error)

        val loginButton = TextView(this).apply {
            text = "LOG IN"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(20, 90, 60)); cornerRadius = dp(12).toFloat() }
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true; isFocusable = true
        }
        loginButton.setOnClickListener {
            val username = usernameField.text.toString().trim()
            val password = passwordField.text.toString()
            if (username.isBlank() || password.isBlank()) { error.text = "Enter your username and password."; return@setOnClickListener }
            loginButton.isEnabled = false; loginButton.text = "LOGGING IN…"
            ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "login: button tapped for user=$username")
            Thread {
                val result = OracleApiClient.login(username, password)
                ro.alintudor.oracle.core.OracleGrowthLog.log(this@OracleMysticActivity, "AUTH", "login: API call returned, success=${result.isSuccess}")
                runOnUiThread {
                    ro.alintudor.oracle.core.OracleGrowthLog.log(this@OracleMysticActivity, "AUTH", "login: on UI thread with result")
                    result.onSuccess { token ->
                        store.saveSession(username, token)
                        ro.alintudor.oracle.core.OracleGrowthLog.log(this@OracleMysticActivity, "AUTH", "login: session saved, registering push token")
                        OracleFirebaseMessagingService.registerCurrentToken(this@OracleMysticActivity)
                        ro.alintudor.oracle.core.OracleGrowthLog.log(this@OracleMysticActivity, "AUTH", "login: starting pullAll")
                        OracleSyncManager.pullAll(this@OracleMysticActivity, token) { pullSuccess ->
                            ro.alintudor.oracle.core.OracleGrowthLog.log(this@OracleMysticActivity, "AUTH", "login: pullAll onDone, success=$pullSuccess")
                            offerBiometricEnrollIfNeeded(store) {
                                ro.alintudor.oracle.core.OracleGrowthLog.log(this@OracleMysticActivity, "AUTH", "login: biometric-enroll step done, calling proceedPastAuth")
                                authPassedThisProcess = true
                                proceedPastAuth()
                            }
                        }
                    }.onFailure {
                        loginButton.isEnabled = true; loginButton.text = "LOG IN"
                        val msg = it.message ?: ""
                        error.text = when {
                            msg.contains("awaiting", true) -> "Your account is awaiting approval by the owner. You'll be notified when it's ready."
                            msg.contains("not approved", true) || msg.contains("rejected", true) || msg.contains("declined", true) -> "This account's access was not approved. Contact the owner if you believe this is a mistake."
                            msg.isNotBlank() -> msg
                            else -> "Wrong username or password."
                        }
                    }
                }
            }.start()
        }
        // Autofill filled the password — if a username is present too, submit
        // on its own, no tap needed. Only fires for genuine autofill, never
        // for manual typing (see authField's onAutofilled contract).
        afterPasswordAutofill = { view ->
            hideKeyboard(view)
            if (usernameField.text.toString().isNotBlank() && loginButton.isEnabled) {
                view.postDelayed({ loginButton.performClick() }, 150L)
            }
        }
        card.addView(loginButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        if (store.biometricEnabled() && store.hasSession() && biometricAvailable()) {
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
            text = "Don't have an account? Register"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(16), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { showRegister(store) }
        })
        card.addView(TextView(this).apply {
            text = "\uD83D\uDD13  TRY THE DEMO \u2014 no account needed"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(gold); setPadding(0, dp(18), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener {
                // A real session (e.g. from biometric) must never run
                // alongside the demo — otherwise the demo's own data
                // operations would sync against the real account.
                store.clearSession()
                ro.alintudor.oracle.core.OracleDemo.enter(this@OracleMysticActivity)
                ro.alintudor.oracle.widget.OracleGrowthWidgetProvider.updateAll(this@OracleMysticActivity)
                authPassedThisProcess = true
                proceedPastAuth()
            }
        })
        card.addView(TextView(this).apply {
            text = "Forgot password?"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(14), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { showForgotPassword(store) }
        })
        card.addView(TextView(this).apply {
            text = "Disclaimer"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.rgb(125, 135, 155)); setPadding(0, dp(14), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { showDisclaimerDialog() }
        })

        scroll.addView(card)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        if (store.biometricEnabled() && store.hasSession() && biometricAvailable()) {
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
            text = "Answer the security questions you set at registration, or use your backup code."
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(10), dp(8), dp(10), dp(24))
        })

        val usernameField = authField(card, "USERNAME", muted, panel, border).apply { setText(store.username()) }
        card.addView(TextView(this).apply {
            text = "Fill in whichever security questions you answered at registration — leave the rest blank."
            textSize = 11f; setTextColor(muted); setPadding(0, dp(14), 0, dp(4))
        })
        val answerFields = OracleAuthStore.SECURITY_QUESTIONS.mapIndexed { index, question ->
            index to authField(card, question, muted, panel, border)
        }.toMap()

        card.addView(TextView(this).apply { text = "— OR —"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(14), 0, dp(2)) })
        val backupField = authField(card, "BACKUP CODE (from registration)", muted, panel, border)
        val newPasswordField = authField(card, "NEW PASSWORD", muted, panel, border, isPassword = true)
        card.addView(TextView(this).apply {
            text = "8\u201316 characters, with an uppercase letter, a lowercase letter, a number, and a symbol. Case-sensitive."
            textSize = 10.5f; setTextColor(muted); setPadding(dp(2), dp(4), dp(2), 0)
        })
        val confirmField = authField(card, "CONFIRM NEW PASSWORD", muted, panel, border, isPassword = true)

        val error = TextView(this).apply { textSize = 12f; setTextColor(red); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        card.addView(error)

        val resetButton = TextView(this).apply {
            text = "RESET PASSWORD"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(20, 90, 60)); cornerRadius = dp(12).toFloat() }
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true; isFocusable = true
        }
        resetButton.setOnClickListener {
            val username = usernameField.text.toString().trim()
            val answers = answerFields.mapValues { it.value.text.toString() }.filterValues { it.isNotBlank() }
            val backupCode = backupField.text.toString()
            val newPassword = newPasswordField.text.toString()
            val confirm = confirmField.text.toString()
            error.text = when {
                username.isBlank() -> "Enter your username."
                passwordRequirementError(newPassword) != null -> passwordRequirementError(newPassword)!!
                newPassword != confirm -> "Passwords don't match."
                answers.isEmpty() && backupCode.isBlank() -> "Answer at least one security question, or provide your backup code."
                else -> ""
            }
            if (error.text.isNotEmpty()) return@setOnClickListener
            resetButton.isEnabled = false; resetButton.text = "RESETTING…"
            Thread {
                val result = OracleApiClient.forgotPassword(username, answers, backupCode, newPassword)
                runOnUiThread {
                    result.onSuccess {
                        Toast.makeText(this@OracleMysticActivity, "Password updated — log in with your new password.", Toast.LENGTH_LONG).show()
                        showLogin(store)
                    }.onFailure {
                        resetButton.isEnabled = true; resetButton.text = "RESET PASSWORD"
                        error.text = it.message ?: "Those answers or that backup code don't match."
                    }
                }
            }.start()
        }
        card.addView(resetButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

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
            contentDescription = "Lux Oculi is starting up"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            android.animation.ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
                duration = 1100L
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
            }.start()
        }
        card.addView(spinner, LinearLayout.LayoutParams(dp(100), dp(100)).apply { gravity = Gravity.CENTER })
        card.addView(TextView(this).apply {
            text = "LUX OCULI"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
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
        var currentQuote = OracleLoaderQuotes.random()
        val quoteLabel = TextView(this).apply {
            text = OracleLoaderQuotes.spanned(currentQuote, gold, muted)
            textSize = 21f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
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

        val quoteRunnable = object : Runnable {
            override fun run() {
                currentQuote = OracleLoaderQuotes.random(excluding = currentQuote)
                quoteLabel.text = OracleLoaderQuotes.spanned(currentQuote, gold, muted)
                mainHandler.postDelayed(this, 1_800L)
            }
        }
        mainHandler.postDelayed(quoteRunnable, 1_800L)

        mainHandler.postDelayed({
            mainHandler.removeCallbacks(quoteRunnable)
            if (!isFinishing) { showHub(); consumePendingModuleIntent() }
        }, bootDurationMs)
    }

    private fun checkAlertsStatusSilently(hero: OracleMysticStartView) {
        val notificationsEnabled = runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()
        }.getOrDefault(false)
        if (!notificationsEnabled) {
            hero.setAlertsStatus("ALERTS OFF", Color.rgb(150, 150, 150))
            return
        }
        // Local-only check: does this device actually have a working FCM
        // token available? No network call to our own server, no push or
        // email sent — purely "is the plumbing on this device functional".
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                runOnUiThread {
                    val ok = task.isSuccessful && !task.result.isNullOrBlank()
                    hero.setAlertsStatus(if (ok) "ALERTS ON" else "ALERTS OFF", if (ok) Color.rgb(105, 245, 35) else Color.rgb(150, 150, 150))
                }
            }
        }.onFailure {
            hero.setAlertsStatus("ALERTS OFF", Color.rgb(150, 150, 150))
        }
    }

    private enum class NonModuleScreen { HUB, TOOLS, ADMIN }
    private var currentNonModuleScreen = NonModuleScreen.HUB

    private fun showHub() {
        currentModule = null
        currentNonModuleScreen = NonModuleScreen.HUB
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
        checkAlertsStatusSilently(hero)
        runCatching {
            val urgent = repository.cachedAlerts()
                .filter { it.active && (it.kind == "URGENT_SELL" || it.kind == "GROWTH_FADING" || it.kind == "HIGH_VOLATILITY") }
                .sortedByDescending { it.timestamp }
                .map { "${it.ticker}: ${it.title}" }
            hero.setUrgentAlerts(urgent)
        }
        checkServerConnectionSilently(hero)
    }

    /** No-auth /ping, on a background thread — works identically whether or
     *  not there's a real session (including in DEMO mode), since it isn't
     *  answering "am I logged in", only "can the app reach the server". */
    private fun checkServerConnectionSilently(hero: OracleMysticStartView) {
        if (ro.alintudor.oracle.core.OracleGrowthEmergency.isForcingLocal(this)) {
            // The real server may well be perfectly reachable — this toggle
            // only tells Growth to act as if it weren't, for testing. Still
            // ping in the background (kept in the network log for reference),
            // but the dot itself reflects what's actually happening right now.
            hero.setServerStatus("SERVER OFF (TEST)", Color.rgb(255, 170, 40))
            Thread { ro.alintudor.oracle.core.OracleApiClient.ping() }.start()
            return
        }
        Thread {
            val ok = ro.alintudor.oracle.core.OracleApiClient.ping().isSuccess
            runOnUiThread {
                hero.setServerStatus(if (ok) "SERVER ON" else "SERVER OFF", if (ok) Color.rgb(105, 245, 35) else Color.rgb(255, 90, 90))
            }
        }.start()
    }

    /** Shared by the START tile and (defensively) by TOOLS' own button — the
     *  exact same dialog either way, so exiting the demo behaves identically
     *  no matter which door was used to reach it. */
    private fun confirmExitDemo() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Exit the demo?")
            .setMessage("The sample portfolio is removed. Create an account to keep your own.")
            .setPositiveButton("Exit") { _, _ ->
                ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "exit-demo confirmed")
                val store = OracleAuthStore(this)
                ro.alintudor.oracle.core.OracleDemo.exit(this)
                store.clearSession()
                ro.alintudor.oracle.widget.OracleGrowthWidgetProvider.updateAll(this)
                authPassedThisProcess = false
                // The real bug behind "stuck on LOGGING IN...": this flag only
                // ever gets set back to false in proceedPastAuth()'s own
                // failure branch. A successful login (including entering the
                // demo, which also calls proceedPastAuth()) leaves it true for
                // the rest of this Activity instance's life — so the very next
                // login, right after exiting demo, always finds it already
                // true and proceedPastAuth() returns immediately without ever
                // showing the boot loader. Must reset here too.
                proceedingPastAuth = false
                currentModule = null
                // Posted rather than called inline: this dialog's own window
                // is still tearing down when this callback runs, and tearing
                // down `root` (then possibly showing a second dialog moments
                // later, e.g. biometric enrollment) right on top of that was
                // the one path where login got stuck on "LOGGING IN..."
                // until the app was backgrounded and resumed.
                root.post { ro.alintudor.oracle.core.OracleGrowthLog.log(this, "AUTH", "exit-demo: showing login screen (posted)"); showLogin(store) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBackupScreen() {
        currentNonModuleScreen = NonModuleScreen.TOOLS
        // TOOLS is closed to a demo visitor — reaching this by any path (deep
        // link, back-stack, a stale reference) redirects straight to the exit
        // prompt instead of ever rendering the screen.
        if (ro.alintudor.oracle.core.OracleDemo.active(this)) { confirmExitDemo(); return }
        root.removeAllViews()
        val bg = Color.rgb(3, 4, 12); val panel = Color.rgb(7, 14, 28); val border = Color.rgb(49, 82, 125)
        val muted = Color.rgb(165, 174, 195); val gold = Color.rgb(255, 205, 55); val green = Color.rgb(105, 245, 35)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(40), dp(28), dp(40)) }

        card.addView(TextView(this).apply { text = "TOOLS"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(gold) })
        card.addView(TextView(this).apply {
            text = "Your data syncs to your account automatically — nothing to manage here."
            textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(26))
        })

        val pushStatus = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        card.addView(TextView(this).apply {
            text = "\uD83D\uDD14  SEND TEST NOTIFICATION"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.rgb(75, 225, 255))
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(75, 225, 255)) }
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true; isFocusable = true
            setOnClickListener {
                val auth = OracleAuthStore(this@OracleMysticActivity)
                if (!auth.hasSession()) { pushStatus.setTextColor(Color.rgb(255, 90, 90)); pushStatus.text = "Not logged in."; return@setOnClickListener }
                pushStatus.setTextColor(muted); pushStatus.text = "Sending…"
                Thread {
                    val result = OracleApiClient.notify(auth.token(), "Lux Oculi test notification", "If you see this on your phone, push notifications are working correctly.")
                    runOnUiThread {
                        result.onSuccess { response ->
                            val push = response.optJSONObject("push")
                            val pushOk = push?.optBoolean("ok", false) ?: false
                            if (pushOk) {
                                pushStatus.setTextColor(green); pushStatus.text = "Email sent, push sent — check your phone."
                            } else {
                                val reason = push?.optString("error")?.takeIf { it.isNotBlank() } ?: "unknown reason"
                                pushStatus.setTextColor(Color.rgb(255, 205, 55))
                                pushStatus.text = "Email sent, but push failed: $reason"
                            }
                        }.onFailure { pushStatus.setTextColor(Color.rgb(255, 90, 90)); pushStatus.text = "Failed: ${it.message}" }
                    }
                }.start()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        card.addView(pushStatus)

        val batteryStatus = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        card.addView(TextView(this).apply {
            text = "WIDGET UPDATE RELIABILITY"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(gold); setPadding(0, dp(28), 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = "The Growth widget refreshes itself every 3 minutes via its own alarm. Android's battery-saving mode can still delay that while the screen is off for a while. Exempting Lux Oculi from battery optimization keeps it closer to the 3-minute cadence — one tap, reversible any time from your phone's own Settings."
            textSize = 11f; setTextColor(muted); setPadding(0, 0, 0, dp(14))
        })
        card.addView(TextView(this).apply {
            text = "\uD83D\uDD0B  DISABLE BATTERY OPTIMIZATION FOR LUX OCULI"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.rgb(105, 245, 35))
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(105, 245, 35)) }
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true; isFocusable = true
            setOnClickListener {
                val pm = getSystemService(android.os.PowerManager::class.java)
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    batteryStatus.setTextColor(green); batteryStatus.text = "Already exempt — the widget gets the best refresh reliability Android allows."
                } else {
                    runCatching {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }.onFailure {
                        batteryStatus.setTextColor(Color.rgb(255, 90, 90))
                        batteryStatus.text = "Couldn't open the system dialog. Try Settings > Apps > Lux Oculi > Battery instead."
                    }
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(batteryStatus)

        // --- Admin Only entry point moved to the very end of this screen,
        // separated from everything else (see below LOG OUT) — it's the
        // owner's own tooling, not part of a regular user's flow through
        // this page, so it shouldn't sit in the middle of it. ---

        card.addView(TextView(this).apply {
            text = "ACCOUNT"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(gold); setPadding(0, dp(28), 0, dp(14))
        })
        // The demo branch here is unreachable — showBackupScreen() itself
        // redirects to confirmExitDemo() before this point whenever the demo
        // is active, so a real logged-in session is the only case left.
        card.addView(TextView(this).apply {
            text = "\uD83D\uDEAA  LOG OUT"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 110, 110))
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(255, 110, 110)) }
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true; isFocusable = true
            setOnClickListener {
                android.app.AlertDialog.Builder(this@OracleMysticActivity)
                    .setTitle("Log out?")
                    .setMessage("You'll need your username and password (or fingerprint, if enabled) to log back in.")
                    .setPositiveButton("Log out") { _, _ ->
                        val store = OracleAuthStore(this@OracleMysticActivity)
                        store.clearSession()
                        ro.alintudor.oracle.core.OracleAdminAccess.lock()
                        ro.alintudor.oracle.widget.OracleGrowthWidgetProvider.updateAll(this@OracleMysticActivity)
                        authPassedThisProcess = false
                        currentModule = null
                        showLogin(store)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(-1, -2))

        if (ro.alintudor.oracle.core.OracleAdminAccess.isOwnerAccount(this)) {
            // A real visual divider, not just spacing — this is deliberately
            // set apart from the rest of TOOLS, which every user sees.
            card.addView(android.view.View(this).apply { setBackgroundColor(Color.rgb(40, 48, 68)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(30); bottomMargin = dp(2) })
            card.addView(TextView(this).apply {
                text = "ADMIN ONLY"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(gold); setPadding(0, dp(14), 0, dp(6))
            })
            card.addView(TextView(this).apply {
                text = "Growth engine log, history, server communication, and the local emergency fallback file."
                textSize = 11f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(6), 0, dp(6), dp(10))
            })
            card.addView(TextView(this).apply {
                text = "\uD83D\uDD11  ADMIN ONLY \u2192"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 170, 40))
                background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(255, 170, 40)) }
                setPadding(0, dp(14), 0, dp(14))
                isClickable = true; isFocusable = true
                setOnClickListener { promptAdminAccess() }
            }, LinearLayout.LayoutParams(-1, -2))
        }

        card.addView(TextView(this).apply {
            text = "← Back"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(24), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { showHub() }
        })

        scroll.addView(card)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    /** Owner-only: fetches every registered user from the server and shows
     *  them in a scrollable dialog with an Approve/Revoke action per row —
     *  the same list and the same two actions the WordPress admin "Oracle
     *  Users" page already has, just reachable without leaving the app. */
    /** Owner-only, experimental. Shows today's UltraShort pick (if the
     *  private ULTRA_SHORT weights found one that beats the real SHORT
     *  pick) with its full argumentation, then the journal — newest-first,
     *  same rule as every other journal — with the hidden performance
     *  check against the +10% / 1\u20133-day target. */
    /** Manual-recovery distribution: if the server is down, email sent FROM
     *  that same server won't go out either — this builds a plain-text
     *  summary of today's cached Growth snapshot and opens Android's native
     *  share sheet, so it reaches people through WhatsApp/Telegram/SMS/a
     *  personal mail app instead, none of it routed through alintudor.ro. */
    private fun shareGrowthSnapshot() {
        val items = runCatching { OracleRepository(this).cachedGrowth() }.getOrDefault(emptyList())
        if (items.isEmpty()) { Toast.makeText(this, "No cached Growth snapshot to share yet.", Toast.LENGTH_SHORT).show(); return }
        val stamp = items.firstOrNull { it.referenceTimestamp > 0L }?.referenceTimestamp
        val stampText = stamp?.let { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(it)) } ?: "unknown time"
        val body = buildString {
            appendLine("LUX OCULI \u2014 Growth Snapshot (manual share)")
            appendLine(stampText)
            appendLine()
            items.sortedWith(compareBy { when (it.horizon) { "SHORT" -> 0; "MEDIUM" -> 1; "LONG" -> 2; else -> 3 } }).forEach { r ->
                appendLine("${r.horizon}: ${r.ticker} \u2014 ${r.signal} (${r.score}/100)")
                appendLine("  Forecast +${String.format(Locale.US, "%.1f", r.forecastPct)}% | Risk: ${r.risk}")
                appendLine()
            }
            append("Not financial advice \u2014 see Lux Oculi's Disclaimer for details.")
        }
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Lux Oculi Growth \u2014 $stampText"); putExtra(Intent.EXTRA_TEXT, body) }
        startActivity(Intent.createChooser(intent, "Share recommendations"))
    }

    private fun showUltraShortDialog() {
        val panel = Color.rgb(7, 14, 28); val muted = Color.rgb(165, 174, 195); val gold = Color.rgb(255, 205, 55)
        val store = ro.alintudor.oracle.core.OracleUltraShortJournalStore(this)
        val entries = store.load()
        val (hits, settled, rate) = store.stats()
        val scroll = ScrollView(this).apply { setBackgroundColor(panel) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }

        list.addView(TextView(this).apply {
            text = if (settled > 0) "TRACK RECORD: $hits of $settled hit +10% \u2014 ${String.format("%.0f", rate)}%"
                   else "TRACK RECORD: no settled entries yet"
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(gold); setPadding(0, 0, 0, dp(14))
        })

        val today = entries.firstOrNull { android.text.format.DateUtils.isToday(it.recommendedAt) }
        if (today != null) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply { setColor(Color.rgb(6, 10, 20)); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(255, 90, 90)) }
            }
            card.addView(TextView(this).apply { text = "TODAY \u2014 ${today.ticker}"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            card.addView(TextView(this).apply {
                text = "Score ${today.score}/100 vs SHORT's ${today.shortScoreBeaten}/100 \u2014 entry \$${String.format("%.2f", today.entryPrice)}"
                textSize = 12f; setTextColor(muted); setPadding(0, dp(4), 0, dp(10))
            })
            if (today.patterns.isNotEmpty()) {
                card.addView(TextView(this).apply { text = "CHART PATTERNS"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(gold); letterSpacing = 0.1f })
                today.patterns.forEach { pat ->
                    card.addView(TextView(this).apply { text = "\u2022 $pat"; textSize = 11f; setTextColor(Color.rgb(210, 216, 230)); setPadding(0, dp(2), 0, 0) })
                }
                card.addView(android.view.View(this), LinearLayout.LayoutParams(-1, dp(10)))
            }
            card.addView(TextView(this).apply { text = "17 FACTOR COMPONENTS"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(gold); letterSpacing = 0.1f })
            val names = ro.alintudor.oracle.nativeui.OracleFactorGrid.NAMES
            today.components.entries.toList().forEachIndexed { i, (_, v) ->
                card.addView(TextView(this).apply {
                    text = "${names.getOrElse(i) { "?" }}: ${v.toInt()}"; textSize = 10.5f; setTextColor(Color.rgb(200, 208, 222)); setPadding(0, dp(1), 0, 0)
                })
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        } else {
            list.addView(TextView(this).apply {
                text = "Nothing today \u2014 either no ULTRA_SHORT weights loaded, or nothing beat the real SHORT pick."
                textSize = 12f; setTextColor(muted); setPadding(0, 0, 0, dp(16))
            })
        }

        if (entries.isNotEmpty()) {
            list.addView(TextView(this).apply { text = "HISTORY \u2022 ${entries.size}"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(muted); letterSpacing = 0.1f; setPadding(0, 0, 0, dp(8)) })
            entries.forEach { e ->
                val statusColor = when (e.targetHit) { true -> Color.rgb(105, 245, 35); false -> Color.rgb(255, 90, 90); null -> Color.rgb(255, 205, 55) }
                val statusText = when (e.targetHit) { true -> "HIT"; false -> "MISS"; null -> "WATCHING" }
                val latestPrice = e.day3Price ?: e.day1Price
                val returnText = latestPrice?.let { " \u2014 ${String.format("%+.1f", (it / e.entryPrice - 1.0) * 100.0)}%" } ?: ""
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = GradientDrawable().apply { setColor(Color.rgb(6, 10, 20)); cornerRadius = dp(9).toFloat(); setStroke(dp(1), statusColor) }
                }
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(TextView(this).apply { text = "${e.ticker}  \u00b7  ${e.score}/100$returnText"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(TextView(this).apply { text = statusText; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(statusColor) })
                row.addView(top)
                row.addView(TextView(this).apply {
                    text = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(e.recommendedAt))
                    textSize = 10f; setTextColor(muted); setPadding(0, dp(2), 0, 0)
                })
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        scroll.addView(list)
        android.app.AlertDialog.Builder(this).setTitle("UltraShort").setView(scroll).setPositiveButton("Close", null).show()
    }

    private fun showUserManagementDialog() {
        val token = OracleAuthStore(this).token()
        if (token.isBlank()) { Toast.makeText(this, "Not logged in.", Toast.LENGTH_SHORT).show(); return }
        val loadingDialog = android.app.AlertDialog.Builder(this).setTitle("Users").setMessage("Loading…").setCancelable(false).show()
        Thread {
            val result = OracleApiClient.listUsers(token)
            runOnUiThread {
                loadingDialog.dismiss()
                if (isFinishing) return@runOnUiThread
                result.onSuccess { response -> renderUserManagementDialog(response.optJSONArray("users") ?: org.json.JSONArray()) }
                    .onFailure { Toast.makeText(this, "Couldn't load users: ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun renderUserManagementDialog(users: org.json.JSONArray) {
        val panel = Color.rgb(7, 14, 28); val muted = Color.rgb(165, 174, 195)
        val scroll = ScrollView(this).apply { setBackgroundColor(panel) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        if (users.length() == 0) {
            list.addView(TextView(this).apply { text = "No accounts registered yet."; setTextColor(muted); textSize = 13f })
        }
        for (i in 0 until users.length()) {
            val u = users.optJSONObject(i) ?: continue
            val id = u.optInt("id")
            val username = u.optString("username")
            val status = u.optString("status", "approved")
            val isOwner = u.optBoolean("isOwner", false)
            val statusColor = when { isOwner -> Color.rgb(255, 205, 55); status == "approved" -> Color.rgb(105, 245, 35); status == "pending" -> Color.rgb(255, 205, 55); else -> Color.rgb(255, 90, 90) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(Color.rgb(6, 10, 20)); cornerRadius = dp(11).toFloat(); setStroke(dp(1), statusColor) }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply { text = username; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(TextView(this).apply { text = if (isOwner) "ADMIN" else status.uppercase(); textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(statusColor) })
            row.addView(top)
            val email = u.optString("notificationEmail")
            row.addView(TextView(this).apply { text = if (email.isBlank()) "No notification email" else email; textSize = 11f; setTextColor(muted); setPadding(0, dp(3), 0, 0) })
            row.addView(TextView(this).apply { text = "Registered ${u.optString("createdAt", "—")}"; textSize = 10f; setTextColor(muted); setPadding(0, dp(2), 0, 0) })
            val actionLog = u.optJSONArray("actionLog")
            if (actionLog != null && actionLog.length() > 0) {
                row.addView(TextView(this).apply {
                    text = "HISTORY"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(muted); letterSpacing = 0.12f
                    setPadding(0, dp(8), 0, dp(2))
                })
                for (h in 0 until minOf(actionLog.length(), 5)) {
                    val entry = actionLog.optJSONObject(h) ?: continue
                    row.addView(TextView(this).apply {
                        text = "${entry.optString("at", "—")}  \u2014  ${entry.optString("action", "")}"
                        textSize = 10f; setTextColor(Color.rgb(190, 197, 214)); setPadding(0, dp(1), 0, 0)
                    })
                }
            }
            if (!isOwner) {
                val actionLabel = if (status == "approved") "REVOKE" else "APPROVE"
                val actionColor = if (status == "approved") Color.rgb(255, 90, 90) else Color.rgb(105, 245, 35)
                row.addView(TextView(this).apply {
                    text = actionLabel; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(actionColor)
                    background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(9).toFloat(); setStroke(dp(1), actionColor) }
                    setPadding(0, dp(8), 0, dp(8)); isClickable = true; isFocusable = true
                    setOnClickListener {
                        val decision = if (status == "approved") "reject" else "approve"
                        val confirmMessage = if (decision == "reject") "Revoke access for $username? They'll be signed out on their next sync." else "Approve $username?"
                        android.app.AlertDialog.Builder(this@OracleMysticActivity)
                            .setTitle(if (decision == "reject") "Revoke access?" else "Approve account?")
                            .setMessage(confirmMessage)
                            .setPositiveButton(if (decision == "reject") "Revoke" else "Approve") { _, _ ->
                                val token = OracleAuthStore(this@OracleMysticActivity).token()
                                Thread {
                                    val result = OracleApiClient.setUserStatus(token, id, decision)
                                    runOnUiThread {
                                        if (isFinishing) return@runOnUiThread
                                        result.onSuccess { Toast.makeText(this@OracleMysticActivity, "$username is now ${if (decision == "approve") "approved" else "revoked"}.", Toast.LENGTH_SHORT).show(); showUserManagementDialog() }
                                            .onFailure { Toast.makeText(this@OracleMysticActivity, "Failed: ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
                                    }
                                }.start()
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        scroll.addView(list)
        android.app.AlertDialog.Builder(this)
            .setTitle("Users (${users.length()})")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Reachable only from inside the already-unlocked Admin Only screen —
     *  changes the existing PIN to a new one. setPin() already just
     *  overwrites, so this needs no separate storage-layer support. */
    private fun showChangePinDialog() {
        val pinField = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Change Admin PIN")
            .setMessage("Enter a new PIN for this device. It replaces the current one immediately.")
            .setView(pinField)
            .setPositiveButton("Save") { _, _ ->
                val pin = pinField.text.toString().trim()
                if (pin.length < 4) { Toast.makeText(this, "Use at least 4 digits.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                ro.alintudor.oracle.core.OracleAdminAccess.setPin(this, pin)
                Toast.makeText(this, "PIN changed.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
        pinField.requestFocus()
        pinField.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(pinField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Gate for the Admin Only screen: PIN entry if one's already set, or a
     *  one-time "create a PIN" flow the first time. Only ever reachable from
     *  a button that itself only renders for the owner's account — this is
     *  the second, independent layer on top of that account check. */
    private fun promptAdminAccess() {
        if (ro.alintudor.oracle.core.OracleAdminAccess.isUnlockedThisProcess()) { showAdminScreen(); return }
        fun focusAndShowKeyboard(field: EditText) {
            field.requestFocus()
            field.post {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        val pinField = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        if (!ro.alintudor.oracle.core.OracleAdminAccess.hasPin(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Set an Admin PIN")
                .setMessage("This PIN protects the Admin Only screen on this device. Choose one you'll remember.")
                .setView(pinField)
                .setPositiveButton("Set PIN") { _, _ ->
                    val pin = pinField.text.toString().trim()
                    if (pin.length < 4) { Toast.makeText(this, "Use at least 4 digits.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                    ro.alintudor.oracle.core.OracleAdminAccess.setPin(this, pin)
                    ro.alintudor.oracle.core.OracleAdminAccess.markUnlocked()
                    showAdminScreen()
                }
                .setNegativeButton("Cancel", null).show()
            focusAndShowKeyboard(pinField)
        } else {
            android.app.AlertDialog.Builder(this)
                .setTitle("Admin PIN")
                .setView(pinField)
                .setPositiveButton("Unlock") { _, _ ->
                    if (ro.alintudor.oracle.core.OracleAdminAccess.verifyPin(this, pinField.text.toString().trim())) {
                        ro.alintudor.oracle.core.OracleAdminAccess.markUnlocked()
                        showAdminScreen()
                    } else Toast.makeText(this, "Wrong PIN.", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Forgot PIN?") { _, _ -> showForgotPinDialog() }
                .setNegativeButton("Cancel", null).show()
            focusAndShowKeyboard(pinField)
        }
    }

    /** Forgot-PIN recovery: since the PIN itself is only reachable to change
     *  from inside the screen it protects, forgetting it would otherwise be
     *  a dead end short of reinstalling. Instead, re-verifying the account
     *  password (which only the real owner knows, and which is checked by
     *  the real server, not just locally) is accepted as proof of identity
     *  — on success the PIN is cleared and the "set a new PIN" flow runs
     *  immediately, same as the very first time. */
    private fun showForgotPinDialog() {
        val passwordField = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Account password"
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Reset Admin PIN")
            .setMessage("Enter your account password to verify it's you — the PIN is cleared and you'll set a new one right after.")
            .setView(passwordField)
            .setPositiveButton("Verify") { _, _ ->
                val password = passwordField.text.toString()
                val username = OracleAuthStore(this).username()
                if (password.isBlank()) { Toast.makeText(this, "Enter your password.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                Thread {
                    val result = OracleApiClient.login(username, password)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        result.onSuccess {
                            ro.alintudor.oracle.core.OracleAdminAccess.clearPin(this)
                            Toast.makeText(this, "Verified — set a new PIN.", Toast.LENGTH_SHORT).show()
                            promptAdminAccess()
                        }.onFailure {
                            Toast.makeText(this, "Wrong password.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
        passwordField.requestFocus()
        passwordField.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(passwordField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Everything that used to sit in plain TOOLS but only ever mattered to
     *  the owner: the engine's own diagnostic log, the recommendation
     *  history, the metadata-only server-call log, and the local emergency
     *  fallback file + force-local testing toggle. See core/OracleAdminAccess.kt
     *  for the two-layer gate that gets here (owner account + this-device PIN). */
    private fun showAdminScreen() {
        currentNonModuleScreen = NonModuleScreen.ADMIN
        root.removeAllViews()
        val bg = Color.rgb(3, 4, 12); val panel = Color.rgb(7, 14, 28)
        val muted = Color.rgb(165, 174, 195); val gold = Color.rgb(255, 205, 55); val green = Color.rgb(105, 245, 35)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(40), dp(28), dp(40)) }

        card.addView(TextView(this).apply { text = "ADMIN ONLY"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.rgb(255, 170, 40)) })
        card.addView(TextView(this).apply {
            text = "Not shown to any other account, and locked behind this device's PIN even on yours."
            textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
        })

        fun toolButton(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(color)
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(11).toFloat(); setStroke(dp(1), color) }
            setPadding(0, dp(12), 0, dp(12)); isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

        // --- Growth engine log ------------------------------------------------
        card.addView(TextView(this).apply {
            text = "GROWTH ENGINE LOG"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(gold); setPadding(0, dp(20), 0, dp(6))
        })
        val logCount = ro.alintudor.oracle.core.OracleGrowthLog.lineCount(this)
        card.addView(TextView(this).apply {
            text = if (logCount == 0) "No entries yet. The engine writes here on every run, scan and ranking."
                   else "$logCount entries recorded — every run, universe resolution, scan, enrichment and pick."
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(6), 0, dp(6), dp(10))
        })
        val logRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logRow.addView(toolButton("VIEW", Color.rgb(55, 215, 255)) { showGrowthLogDialog() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(5), 0) })
        logRow.addView(toolButton("DOWNLOAD", green) {
            val path = ro.alintudor.oracle.core.OracleGrowthLog.export(this)
            Toast.makeText(this, if (path != null) "Growth log saved to Downloads." else "The log is empty — nothing to export yet.", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
        logRow.addView(toolButton("CLEAR", Color.rgb(255, 140, 140)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Clear the Growth log?")
                .setMessage("The recorded history of engine runs is deleted. New entries start from the next run.")
                .setPositiveButton("Clear") { _, _ ->
                    ro.alintudor.oracle.core.OracleGrowthLog.clear(this)
                    Toast.makeText(this, "Growth log cleared.", Toast.LENGTH_SHORT).show()
                    showAdminScreen()
                }
                .setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, 0, 0) })
        card.addView(logRow, LinearLayout.LayoutParams(-1, -2))

        // --- Growth history (the "LATEST RECOMMENDATIONS" journal) ------------
        card.addView(TextView(this).apply {
            text = "GROWTH HISTORY"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(gold); setPadding(0, dp(28), 0, dp(6))
        })
        val journalStore = ro.alintudor.oracle.core.OracleGrowthJournalStore(this)
        val journalCount = journalStore.load().size
        card.addView(TextView(this).apply {
            text = if (journalCount == 0) "No recorded recommendations yet."
                   else "$journalCount recommendations recorded — shown as \"Latest recommendations\" on Growth."
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(6), 0, dp(6), dp(10))
        })
        card.addView(toolButton("CLEAR HISTORY", Color.rgb(255, 140, 140)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Clear the Growth history?")
                .setMessage("Every past recommendation entry is deleted — this also resets the Performance track record. New entries start from the next pick.")
                .setPositiveButton("Clear") { _, _ ->
                    journalStore.clear()
                    Toast.makeText(this, "Growth history cleared.", Toast.LENGTH_SHORT).show()
                    showAdminScreen()
                }
                .setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(-1, -2))

        // --- Server communication (metadata-only network log) -----------------
        card.addView(TextView(this).apply {
            text = "SERVER COMMUNICATION"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(gold); setPadding(0, dp(28), 0, dp(6))
        })
        val netLogCount = ro.alintudor.oracle.core.OracleNetworkLog.lineCount()
        card.addView(TextView(this).apply {
            text = if (netLogCount == 0) "No calls recorded yet."
                   else "$netLogCount calls recorded — endpoint, timestamp and outcome only, never a real value."
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(6), 0, dp(6), dp(10))
        })
        val netLogRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        netLogRow.addView(toolButton("VIEW", Color.rgb(55, 215, 255)) { showNetworkLogDialog() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(5), 0) })
        netLogRow.addView(toolButton("CLEAR", Color.rgb(255, 140, 140)) {
            ro.alintudor.oracle.core.OracleNetworkLog.clear()
            Toast.makeText(this, "Server communication log cleared.", Toast.LENGTH_SHORT).show()
            showAdminScreen()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, 0, 0) })
        card.addView(netLogRow, LinearLayout.LayoutParams(-1, -2))

        // --- Growth local emergency (owner-only fallback data, see core/OracleGrowthEmergency.kt) ---
        card.addView(TextView(this).apply {
            text = "GROWTH LOCAL EMERGENCY"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(gold); setPadding(0, dp(28), 0, dp(6))
        })
        val emergencyLoaded = ro.alintudor.oracle.core.OracleGrowthEmergency.isLoaded(this)
        val emergencyLoadedAt = ro.alintudor.oracle.core.OracleGrowthEmergency.loadedAt(this)
        card.addView(TextView(this).apply {
            text = if (!emergencyLoaded) "Not loaded — the on-device fallback engine uses its built-in data."
                   else "Loaded " + android.text.format.DateFormat.format("d MMM yyyy, HH:mm", emergencyLoadedAt ?: 0L) + " — the fallback engine uses this file's data."
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(6), 0, dp(6), dp(10))
        })
        val emergencyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        emergencyRow.addView(toolButton("LOAD FILE", Color.rgb(55, 215, 255)) {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "*/*"
                // No MIME-type restriction: many file managers/storage
                // providers don't tag .json files as application/json (some
                // use text/plain, some leave it generic), so filtering by
                // MIME type risks hiding the very file being looked for —
                // confirmed exactly that just happened. The real gatekeeper
                // is importFrom()'s own content validation below, which
                // already rejects anything that isn't the expected shape
                // with a clear message — that's more reliable than MIME
                // sniffing ever is.
            }
            runCatching { startActivityForResult(intent, EMERGENCY_IMPORT_REQUEST) }.onFailure { Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(5), 0) })
        emergencyRow.addView(toolButton("CLEAR", Color.rgb(255, 140, 140)) {
            if (!emergencyLoaded) { Toast.makeText(this, "Nothing loaded.", Toast.LENGTH_SHORT).show(); return@toolButton }
            android.app.AlertDialog.Builder(this)
                .setTitle("Clear the loaded emergency file?")
                .setMessage("The fallback engine goes back to its built-in data.")
                .setPositiveButton("Clear") { _, _ ->
                    ro.alintudor.oracle.core.OracleGrowthEmergency.clear(this)
                    Toast.makeText(this, "Emergency file cleared.", Toast.LENGTH_SHORT).show()
                    showAdminScreen()
                }
                .setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, 0, 0) })
        card.addView(emergencyRow, LinearLayout.LayoutParams(-1, -2))

        val forcingLocal = ro.alintudor.oracle.core.OracleGrowthEmergency.isForcingLocal(this)
        card.addView(TextView(this).apply {
            text = if (forcingLocal) "Testing: forced onto local computation — Growth ignores the server until this is turned off."
                   else "Growth uses the server normally. Force local to test a loaded file without touching the server."
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(if (forcingLocal) Color.rgb(255, 170, 40) else muted); setPadding(dp(6), dp(10), dp(6), dp(8))
        })
        card.addView(toolButton(if (forcingLocal) "TURN OFF — USE SERVER AGAIN" else "FORCE LOCAL MODE (TESTING)", if (forcingLocal) Color.rgb(255, 170, 40) else Color.rgb(55, 215, 255)) {
            val turningOn = !forcingLocal
            ro.alintudor.oracle.core.OracleGrowthEmergency.setForceLocal(this, turningOn)
            Toast.makeText(this, if (turningOn) "Forced local — today's Growth snapshot cleared, next open recomputes on-device." else "Back to normal — Growth will try the server again.", Toast.LENGTH_LONG).show()
            showAdminScreen()
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(TextView(this).apply {
            text = "If the server itself is down, email sent from alintudor.ro won't go out either — this shares the same recommendations through whatever's on this phone instead (WhatsApp, Telegram, SMS, a personal email app)."
            textSize = 10.5f; setTextColor(muted); setPadding(dp(6), dp(8), dp(6), dp(6))
        })
        card.addView(toolButton("SHARE RECOMMENDATIONS", Color.rgb(105, 245, 35)) { shareGrowthSnapshot() }, LinearLayout.LayoutParams(-1, -2))

        // --- User management (list, approve, revoke — mirrors the WP admin page) ---
        card.addView(TextView(this).apply {
            text = "USER MANAGEMENT"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(gold); setPadding(0, dp(28), 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = "Every registered account, with the same approve/revoke controls as the WordPress admin page."
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(6), 0, dp(6), dp(10))
        })
        card.addView(toolButton("VIEW USERS", Color.rgb(55, 215, 255)) { showUserManagementDialog() }, LinearLayout.LayoutParams(-1, -2))

        // --- Change the Admin PIN itself --------------------------------------
        card.addView(TextView(this).apply {
            text = "ADMIN PIN"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(gold); setPadding(0, dp(28), 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = "This device's PIN for unlocking this screen."
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(6), 0, dp(6), dp(10))
        })
        card.addView(toolButton("CHANGE PIN", Color.rgb(255, 170, 40)) { showChangePinDialog() }, LinearLayout.LayoutParams(-1, -2))

        // --- UltraShort: experimental, owner-only ------------------------------
        card.addView(TextView(this).apply {
            text = "ULTRASHORT"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(gold); setPadding(0, dp(28), 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = "Experimental. Only ever appears when today's scan finds something that beats the real SHORT pick under your private ULTRA_SHORT weights — target: +10% within 1\u20133 trading days."
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(6), 0, dp(6), dp(10))
        })
        card.addView(toolButton("VIEW ULTRASHORT", Color.rgb(255, 90, 90)) { showUltraShortDialog() }, LinearLayout.LayoutParams(-1, -2))

        card.addView(TextView(this).apply {
            text = "\u2190 Back to Tools"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(28), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { showBackupScreen() }
        })

        scroll.addView(card)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            ro.alintudor.oracle.nativeui.OraclePortfolioModule.CSV_IMPORT_REQUEST -> Thread {
                val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" }.getOrDefault("")
                val rows = runCatching { ro.alintudor.oracle.core.OracleCsvImport.parse(text) }.getOrDefault(emptyList())
                val n = if (rows.isNotEmpty()) ro.alintudor.oracle.nativeui.OraclePortfolioModule.applyImport(this, rows) else 0
                runOnUiThread {
                    if (n == 0) Toast.makeText(this, "No positions recognized in that file (needs ticker, quantity and price columns).", Toast.LENGTH_LONG).show()
                    else { Toast.makeText(this, "Imported $n position${if (n == 1) "" else "s"} — prices refresh now.", Toast.LENGTH_LONG).show(); currentModule = null; openModule("portfolio") }
                }
            }.start()
            EMERGENCY_IMPORT_REQUEST -> Thread {
                val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" }.getOrDefault("")
                val summary = ro.alintudor.oracle.core.OracleGrowthEmergency.importFrom(this, text)
                runOnUiThread {
                    if (summary == null) Toast.makeText(this, "That file didn't match the expected format — nothing changed.", Toast.LENGTH_LONG).show()
                    else { Toast.makeText(this, summary, Toast.LENGTH_LONG).show(); currentModule = null; showAdminScreen() }
                }
            }.start()
        }
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
        if (key == "disclaimer") { showDisclaimerDialog(); return }
        if (key == "backup") {
            if (ro.alintudor.oracle.core.OracleDemo.active(this)) { confirmExitDemo(); return }
            currentModule = "backup"; showBackupScreen(); return
        }
        // A press on the refresh button re-enters this same function with the
        // same key the screen is already showing — distinct from a genuine
        // navigation into the module, where the screen must render immediately
        // (with cached data) so it isn't blank while the background refresh runs.
        val isRefreshOfOpenScreen = currentModule == key
        currentModule = key
        if (key == "alerts" && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 357)
        }
        // On a refresh of an already-open screen, skip this immediate render:
        // the screen already shows this exact data, so redrawing it here only
        // to redraw it again a moment later (once the background refresh below
        // lands) is a redundant rebuild.
        if (!isRefreshOfOpenScreen) runCatching { renderModule(key) }.onFailure { showModuleError(key, it) }

        // GROWTH is a live, independent module. The real launcher is
        // OracleMysticActivity, so Growth must be calculated here rather than
        // relying on the dead MainActivity path or the general refresh chain.
        if (key == "growth") {
            Thread {
                val result = runCatching { OracleLocalProcessor.refreshGrowthOnly(repository) }
                mainHandler.post {
                    if (currentModule != "growth" || isFinishing) return@post
                    result.onSuccess {
                        // By this point currentModule == "growth" is already confirmed
                        // above — the screen is showing Growth right now, whether the
                        // user just navigated in or this is a later refresh. Either
                        // way this specific render is an in-place UPDATE of what's
                        // already on screen, never a fresh entrance, so reuseHost is
                        // unconditionally true here — NOT tied to isRefreshOfOpenScreen,
                        // which only reflects what was true back when this background
                        // fetch started (false on first navigation), and using that
                        // stale value is what caused a full flashy rebuild — complete
                        // with replayed entrance animations — a few seconds after every
                        // fresh navigation into Growth, even though the user was
                        // already sitting there looking at the cached first paint.
                        runCatching { renderModule("growth", silent = true, reuseHost = true) }
                            .onFailure { showModuleError("growth", it) }
                    }.onFailure { error ->
                        if (!handleUnauthorizedIfNeeded(error)) showGrowthCalculationError(error)
                    }
                }
            }.start()
            return
        }

        if (key == "analysis") {
            // Analysis has no background refresh step, so a refresh press here
            // needs its own explicit render (the general skip above only defers
            // to a render that happens later — there is no "later" for Analysis).
            if (isRefreshOfOpenScreen) runCatching { renderModule(key, reuseHost = true) }.onFailure { showModuleError(key, it) }
            return
        }
        Thread {
            val result = runCatching { OracleLocalProcessor.refresh(repository) }
            mainHandler.post {
                if (currentModule != key || isFinishing) return@post
                // Same fix as Growth above: currentModule == key on the line just
                // above already proves this module is on screen right now, so this
                // completion render is always an in-place update — silent=true,
                // reuseHost=true unconditionally, not gated on isRefreshOfOpenScreen
                // (which reflects the state from before this background fetch even
                // started, back when a first-time navigation hadn't shown anything
                // yet). That stale check was why every fresh navigation into News,
                // Watchlist, Alerts, Journal, Portfolio, or Knowledge quietly did a
                // full rebuild — complete with a fresh ScrollView and replayed
                // entrance animations — a few seconds after the screen first
                // appeared, the moment the background fetch happened to finish:
                // exactly the "hidden refresh" flicker, on every one of those
                // modules, and only those, since Analysis has no such step at all.
                result.onSuccess { runCatching { renderModule(key, silent = true, reuseHost = true) }.onFailure { showModuleError(key, it) } }
                    .onFailure { error -> if (!handleUnauthorizedIfNeeded(error)) Toast.makeText(this, "Local refresh failed: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** The currently mounted module shell, kept so a refresh can update its
     *  content in place (reuseHost) instead of tearing down and rebuilding the
     *  ScrollView — the only way to guarantee the scroll position truly cannot
     *  move. Always discarded on the next fresh navigation (reuseHost = false
     *  rebuilds and replaces it), and implicitly invalidated by showHub()
     *  setting currentModule = null, which makes isRefreshOfOpenScreen false
     *  for any subsequent openModule() call. */
    private var activeHost: OracleNativeModule? = null

    private fun renderModule(key: String, silent: Boolean = false, reuseHost: Boolean = false) {
        val moduleTitle = titles[key] ?: key.uppercase()
        val existing = activeHost
        val host = if (reuseHost && existing != null) existing else {
            root.removeAllViews()
            val fresh = OracleNativeModule(this, moduleTitle, { showHub() }, { openModule(key) }, moduleKey = key)
            root.addView(fresh.root, FrameLayout.LayoutParams(-1, -1))
            activeHost = fresh
            fresh
        }
        val data = repository.snapshot()
        when (key) {
            "portfolio" -> OraclePortfolioModule(host).render(data.positions, silent)
            "alerts" -> OracleAlertsModule(host).render(data.alerts)
            "news" -> OracleNewsModule(host).render(data.news, silent)
            "journal" -> OracleJournalModule(host).render(data.journal, data.history, data.alerts)
            "growth", "analysis", "watchlist", "knowledge" -> OracleSimpleModule(
                host,
                moduleTitle,
                onWatchlistTickerClick = { ticker -> openWatchlistTicker(ticker) }
            ).render(actions = data.actions, knowledge = data.knowledge, positions = data.positions, history = data.history, silent = silent)
        }

    }

    private fun openWatchlistTicker(ticker: String) {
        val normalized = ticker.trim().uppercase(java.util.Locale.US)
        if (normalized.isBlank()) return
        OracleSimpleModule.setTickerDraft(normalized)
        openModule("analysis")
    }

    /** Checks whether `error` is (or wraps) a server 401 — the account behind
     *  this session's token is no longer "approved" (revoked by the owner,
     *  or never approved). If so, clears the stale session and routes to
     *  login with a clear reason instead of leaving the caller's own normal
     *  error handling to show a generic, repeatedly-failing sync error.
     *  Returns true if it handled the error (caller should skip its own
     *  failure handling in that case). */
    private fun handleUnauthorizedIfNeeded(error: Throwable): Boolean {
        var e: Throwable? = error
        while (e != null) {
            if (e is ro.alintudor.oracle.core.OracleUnauthorizedException) {
                val store = OracleAuthStore(this)
                store.clearSession()
                ro.alintudor.oracle.core.OracleAdminAccess.lock()
                ro.alintudor.oracle.widget.OracleGrowthWidgetProvider.updateAll(this)
                authPassedThisProcess = false
                currentModule = null
                showLogin(store)
                Toast.makeText(this, "This account no longer has access. Please contact the owner if you believe this is a mistake.", Toast.LENGTH_LONG).show()
                return true
            }
            e = e.cause
        }
        return false
    }

    private var lastBackHandledAtMs = 0L
    private fun handleBack() {
        val now = System.currentTimeMillis()
        if (now - lastBackHandledAtMs < 400L) return
        lastBackHandledAtMs = now
        when {
            currentModule != null -> showHub()
            currentNonModuleScreen == NonModuleScreen.ADMIN -> showBackupScreen()
            currentNonModuleScreen == NonModuleScreen.TOOLS -> showHub()
            else -> finish()
        }
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
            text = "BACK TO LUX OCULI"
            setOnClickListener { showHub() }
        })
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showModuleError(key: String, error: Throwable) {
        root.removeAllViews()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)); setBackgroundColor(Color.rgb(3, 5, 12)) }
        box.addView(TextView(this).apply { text = "LUX OCULI  •  ${titles[key] ?: key.uppercase()}"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) })
        box.addView(TextView(this).apply { text = "The module could not be loaded.\n\n${error.message ?: error.javaClass.simpleName}"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY); setPadding(0, dp(24), 0, dp(24)) })
        box.addView(Button(this).apply { text = "RETRY"; setOnClickListener { openModule(key) } })
        box.addView(Button(this).apply { text = "BACK TO LUX OCULI"; setOnClickListener { showHub() } })
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
