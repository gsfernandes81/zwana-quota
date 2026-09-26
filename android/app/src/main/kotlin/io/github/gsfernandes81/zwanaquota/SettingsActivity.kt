package io.github.gsfernandes81.zwanaquota

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.gsfernandes81.zwanaquota.core.Credentials
import io.github.gsfernandes81.zwanaquota.core.PortalClient
import java.util.concurrent.Executors

/**
 * Sign in, switch the watch on, and see what the app has been doing.
 *
 * The lower half is the diagnostics: the latest word on every subject (the
 * portal read, the watch send, the background worker, Garmin Connect) and the
 * journal behind them. Nobody using this has logcat, so anything that can
 * fail says so here, in words, including the failures that are not the app's
 * -- Garmin Connect missing, the watch disconnected, the phone putting the
 * app to sleep.
 *
 * Built in code rather than XML: it is a form and a log, and this keeps the
 * whole screen in one place.
 */
class SettingsActivity : Activity() {
    private val store by lazy { Store(this) }
    private val vault by lazy { Vault(this) }
    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var account: TextView
    private lateinit var watch: Switch
    private lateinit var diagnostics: TextView
    private var garminLines: List<String> = emptyList()

    private val tick = object : Runnable {
        override fun run() {
            showDiagnostics()
            main.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }

        column += heading("Portal login")
        account = TextView(this)
        column += account
        username = EditText(this).apply {
            hint = "zwana_username"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        password = EditText(this).apply {
            hint = "zwana_password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        column += username
        column += password
        column += row(
            button("Save and read") { save() },
            button("Sign out") {
                vault.signOut()
                store.note("account", "signed out")
                showAccount()
                QuotaWidget.draw(this, Refresher.cachedFace(this), busy = false)
            },
        )
        column += note("Kept on this phone only, encrypted with a key in the Android Keystore. " +
            "The portal is ${PortalClient.PORTAL_URL}")

        column += heading("Garmin watch")
        watch = Switch(this).apply {
            text = "Send the reading to the watch every ${Refresher.EVERY_MINUTES} minutes, and on every tap"
            isChecked = store.watchEnabled
            setOnCheckedChangeListener { _, on ->
                store.watchEnabled = on
                Work.schedule(this@SettingsActivity)
                store.note("watch", if (on) "switched on" else "switched off")
                if (on) Work.pushNow(this@SettingsActivity)
            }
        }
        column += watch
        column += row(
            button("Send now") { Work.pushNow(this) },
            button("Check watch") { checkWatch() },
        )
        column += note("Needs Garmin Connect signed in and the zwana quota watch app installed and opened once. " +
            "The widget works without any of it. Watch app id: ${Garmin.APP_ID}")

        column += heading("Diagnostics")
        column += row(
            button("Read now") { Work.refresh(this, force = true, trigger = "button") },
            button("Share log") { shareLog() },
        )
        column += button("Battery: let it run in the background") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        diagnostics = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        column += diagnostics

        setContentView(ScrollView(this).apply {
            // Edge to edge is enforced from Android 15; this keeps the form
            // out from under the status and navigation bars.
            fitsSystemWindows = true
            addView(column)
        })
        showAccount()
    }

    override fun onResume() {
        super.onResume()
        main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        background.shutdown()
        super.onDestroy()
    }

    private fun save() {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            account.text = "Enter both the username and the password."
            return
        }
        vault.setCredentials(Credentials(user, pass))
        password.text.clear()
        store.note("account", "login saved for $user")
        showAccount()
        Work.refresh(this, force = true, trigger = "sign-in")
    }

    private fun showAccount() {
        val login = vault.credentials()
        account.text = if (login == null) "Not signed in." else "Signed in as ${login.username}."
        if (login != null && username.text.isEmpty()) username.setText(login.username)
    }

    /** Straight from this screen rather than through the worker, so the two paths can be told apart. */
    private fun checkWatch() {
        garminLines = listOf("checking...")
        background.execute {
            val lines = try {
                Garmin.check(applicationContext)
            } catch (e: Exception) {
                listOf("check failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            }
            store.log("check: ${lines.joinToString("; ")}")
            main.post { garminLines = lines }
        }
    }

    private fun showDiagnostics() {
        val power = getSystemService(PowerManager::class.java)
        val unrestricted = power?.isIgnoringBatteryOptimizations(packageName) == true
        val text = buildString {
            appendLine("reading  ${store.reading()?.let { "remainder ${it.remainder} B, taken at ts ${it.ts.toLong()}" } ?: "none"}")
            store.notes().forEach { (subject, line) -> appendLine("${subject.padEnd(8)} $line") }
            appendLine("battery  ${if (unrestricted) "unrestricted" else "optimised: periodic sends may be held back"}")
            appendLine("garmin   ${Garmin.state}")
            garminLines.forEach { appendLine("         $it") }
            appendLine()
            appendLine("journal (newest last)")
            append(store.journal().lines().takeLast(60).joinToString("\n"))
        }
        if (diagnostics.text.toString() != text) diagnostics.text = text
    }

    private fun shareLog() {
        val body = buildString {
            appendLine("zwana quota ${packageManager.getPackageInfo(packageName, 0).versionName}")
            store.notes().forEach { (subject, line) -> appendLine("$subject: $line") }
            appendLine("garmin: ${Garmin.state}")
            garminLines.forEach { appendLine("  $it") }
            appendLine()
            append(store.journal())
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
        startActivity(Intent.createChooser(send, "Share the zwana quota log"))
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        alpha = 0.7f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private operator fun LinearLayout.plusAssign(view: View) = addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
