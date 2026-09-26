package io.github.gsfernandes81.zwanaquota

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import io.github.gsfernandes81.zwanaquota.core.Credentials
import io.github.gsfernandes81.zwanaquota.core.Face
import io.github.gsfernandes81.zwanaquota.core.Format
import io.github.gsfernandes81.zwanaquota.core.Level
import io.github.gsfernandes81.zwanaquota.core.Pipeline
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * The app's one screen: today's reading, the portal login, the watch, and
 * what the app has been doing.
 *
 * The reading leads, drawn from the same [Face] the widget draws, so the two
 * never disagree. Its state is a chip with an icon and a label as well as a
 * colour, so it never rests on colour alone. The diagnostics are folded away
 * until asked for. They are still the only window anyone has into this app,
 * since nobody using it has logcat, so every failure that can happen says so
 * there in words, including the ones that are not the app's: Garmin Connect
 * missing, the watch disconnected, the phone putting the app to sleep.
 */
class SettingsActivity : AppCompatActivity() {
    private val store by lazy { Store(this) }
    private val vault by lazy { Vault(this) }
    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val figure by view<TextView>(R.id.figure)
    private val level by view<Chip>(R.id.level)
    private val freshness by view<Chip>(R.id.freshness)
    private val meter by view<LinearProgressIndicator>(R.id.meter)
    private val status by view<TextView>(R.id.status)
    private val reset by view<TextView>(R.id.reset)
    private val footnote by view<TextView>(R.id.footnote)
    private val readNow by view<MaterialButton>(R.id.read_now)
    private val account by view<TextView>(R.id.account)
    private val loginForm by view<View>(R.id.login_form)
    private val signedInActions by view<View>(R.id.signed_in_actions)
    private val username by view<TextInputEditText>(R.id.username)
    private val password by view<TextInputEditText>(R.id.password)
    private val watchSwitch by view<MaterialSwitch>(R.id.watch_switch)
    private val watchStatus by view<TextView>(R.id.watch_status)
    private val watchCheck by view<TextView>(R.id.watch_check)
    private val diagnosticsBody by view<View>(R.id.diagnostics_body)
    private val diagnosticsSummary by view<TextView>(R.id.diagnostics_summary)
    private val chevron by view<ImageView>(R.id.diagnostics_chevron)
    private val rows by view<LinearLayout>(R.id.rows)
    private val battery by view<MaterialButton>(R.id.battery)
    private val journal by view<TextView>(R.id.journal)

    /** Changing the login shows the form again while still signed in. */
    private var editingLogin = false

    /** The portal note when "Read now" was pressed; the read is over when it changes. */
    private var readingSince: String? = null
    private var readingStarted = 0L

    private var garminLines: List<String> = emptyList()

    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The wallpaper's colours where the phone has them (Android 12+), the
        // teal fallback in themes.xml where it does not.
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        // Edge to edge: the app bar takes the status bar's inset itself; the
        // scrolling column keeps its last card clear of the navigation bar.
        val scroll = findViewById<NestedScrollView>(R.id.scroll)
        val bottom = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, v.paddingTop, bars.right, bottom + bars.bottom)
            insets
        }

        readNow.setOnClickListener {
            readingSince = store.notes()["read"]
            readingStarted = System.currentTimeMillis()
            Work.refresh(this, force = true, trigger = "button")
            render()
        }

        findViewById<MaterialButton>(R.id.sign_in).setOnClickListener { signIn() }
        findViewById<MaterialButton>(R.id.change_login).setOnClickListener {
            editingLogin = true
            render()
        }
        findViewById<MaterialButton>(R.id.sign_out).setOnClickListener {
            vault.signOut()
            editingLogin = false
            username.text?.clear()
            store.note("account", "signed out")
            QuotaWidget.draw(this, Refresher.cachedFace(this), busy = false)
            render()
        }

        watchSwitch.isChecked = store.watchEnabled
        watchSwitch.setOnCheckedChangeListener { _, on ->
            store.watchEnabled = on
            Work.schedule(this)
            store.note("watch", if (on) "switched on" else "switched off")
            if (on) Work.pushNow(this)
            render()
        }
        findViewById<MaterialButton>(R.id.send_now).setOnClickListener { Work.pushNow(this) }
        findViewById<MaterialButton>(R.id.check_watch).setOnClickListener { checkWatch() }

        findViewById<View>(R.id.diagnostics_header).setOnClickListener {
            val open = diagnosticsBody.visibility != View.VISIBLE
            diagnosticsBody.visibility = if (open) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (open) 180f else 0f).setDuration(150).start()
        }
        battery.setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        findViewById<MaterialButton>(R.id.share_log).setOnClickListener { shareLog() }

        vault.credentials()?.let { username.setText(it.username) }
        render()
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

    /** Everything on the screen, from what is stored. Cheap, so it runs every two seconds. */
    private fun render() {
        renderReading()
        renderAccount()
        renderWatch()
        renderDiagnostics()
    }

    private fun renderReading() {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val reading = store.reading()
        val doc = reading?.let { Pipeline.derive(it, Pipeline.epochSeconds(now) - it.ts, false, now) }
        val face = doc?.let { Face.of(it, zone) } ?: Refresher.cachedFace(this)
        figure.text = face.figure
        // The widget's face puts the age in front of the share, because it has
        // no chips; here the freshness chip says it, so the line keeps to the
        // share and nothing is said twice.
        status.text = doc?.let {
            getString(
                R.string.share_of_pool,
                Format.percent(it.remainderBytes.toDouble() / maxOf(1L, it.poolBytes)),
                Format.size(it.poolBytes),
            )
        } ?: face.status
        reset.text = face.reset
        footnote.text = face.footnote

        val colour = ContextCompat.getColor(this, statusColour(face.level))
        val (icon, label) = when {
            doc == null -> R.drawable.ic_unknown to getString(R.string.level_unknown)
            face.level == Level.OK -> R.drawable.ic_ok to getString(R.string.level_ok)
            face.level == Level.LOW -> R.drawable.ic_low to getString(R.string.level_low)
            else -> R.drawable.ic_critical to getString(R.string.level_critical)
        }
        chip(level, icon, label, colour)

        val mark = doc?.let { Face.mark(it) }
        freshness.visibility = if (mark == null) View.GONE else View.VISIBLE
        if (mark != null) {
            val grey = ContextCompat.getColor(this, R.color.status_unknown)
            if (mark == "offline") chip(freshness, R.drawable.ic_offline, getString(R.string.offline), grey)
            else chip(freshness, R.drawable.ic_stale, getString(R.string.out_of_date, mark), grey)
        }

        val share = doc?.let { it.remainderBytes.toDouble() / maxOf(1L, it.poolBytes) } ?: 0.0
        meter.setProgressCompat((share.coerceIn(0.0, 1.0) * 1000).toInt(), false)
        meter.setIndicatorColor(colour)
        // The unfilled track is a lighter step of the fill's own colour, so the
        // state reads across the whole bar and not only its filled part.
        meter.trackColor = ColorUtils.setAlphaComponent(colour, 0x3D)

        val busy = readingSince != null &&
            store.notes()["read"] == readingSince &&
            System.currentTimeMillis() - readingStarted < READ_TIMEOUT_MS
        if (!busy) readingSince = null
        // Signed out, reading is what "Sign in and read" does; this would only fail.
        readNow.visibility = if (vault.signedIn) View.VISIBLE else View.GONE
        readNow.isEnabled = !busy
        readNow.text = getString(if (busy) R.string.reading else R.string.read_now)
    }

    private fun chip(chip: Chip, @DrawableRes icon: Int, label: String, colour: Int) {
        chip.text = label
        chip.setChipIconResource(icon)
        chip.chipIconTint = ColorStateList.valueOf(colour)
        chip.contentDescription = label
    }

    /**
     * A note as a person reads it: `16:23 ok over Wi-Fi` today, with the date
     * only when it is not today. The stored stamp is `MM-dd HH:mm:ss`.
     */
    private fun readable(note: String?): String? {
        val parts = note?.split(' ', limit = 3) ?: return null
        if (parts.size < 3 || parts[0].length != 5 || parts[1].length != 8) return note
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
        val time = parts[1].take(5)
        return if (parts[0] == today) "$time  ${parts[2]}" else "${parts[0]} $time  ${parts[2]}"
    }

    @ColorRes
    private fun statusColour(level: Level): Int = when (level) {
        Level.OK -> R.color.status_good
        Level.LOW -> R.color.status_warning
        Level.CRITICAL -> R.color.status_critical
        Level.UNKNOWN -> R.color.status_unknown
    }

    private fun renderAccount() {
        val login = vault.credentials()
        val showForm = login == null || editingLogin
        account.text = if (login == null) getString(R.string.signed_out) else getString(R.string.signed_in_as, login.username)
        loginForm.visibility = if (showForm) View.VISIBLE else View.GONE
        signedInActions.visibility = if (showForm) View.GONE else View.VISIBLE
    }

    private fun renderWatch() {
        val last = readable(store.notes()["watch"])
        watchStatus.text = when {
            last != null -> last
            store.watchEnabled -> getString(R.string.watch_never)
            else -> getString(R.string.watch_off)
        }
        watchCheck.visibility = if (garminLines.isEmpty()) View.GONE else View.VISIBLE
        watchCheck.text = garminLines.joinToString("\n")
    }

    private fun renderDiagnostics() {
        val notes = store.notes()
        val unrestricted = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        diagnosticsSummary.text = readable(notes["read"])?.let { getString(R.string.last_read, it) }
            ?: getString(R.string.nothing_yet)

        val table = listOf(
            R.string.row_portal to (readable(notes["read"]) ?: getString(R.string.nothing_yet)),
            R.string.row_watch to (readable(notes["watch"]) ?: getString(R.string.nothing_yet)),
            R.string.row_background to (readable(notes["worker"]) ?: getString(R.string.nothing_yet)),
            R.string.row_garmin to Garmin.state,
            R.string.row_battery to getString(if (unrestricted) R.string.battery_free else R.string.battery_held),
            R.string.row_version to (packageManager.getPackageInfo(packageName, 0).versionName ?: "?"),
        )
        if (rows.childCount != table.size) {
            rows.removeAllViews()
            table.forEach { rows.addView(row()) }
        }
        table.forEachIndexed { i, (label, value) ->
            val row = rows.getChildAt(i) as LinearLayout
            (row.getChildAt(0) as TextView).setText(label)
            (row.getChildAt(1) as TextView).text = value
        }
        battery.visibility = if (unrestricted) View.GONE else View.VISIBLE

        val text = store.journal().trimEnd().lines().takeLast(JOURNAL_LINES).joinToString("\n")
        if (journal.text.toString() != text) journal.text = text.ifEmpty { getString(R.string.nothing_yet) }
    }

    /** One label/value line of the diagnostics table. */
    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(context, null, 0, R.style.Zwana_Label), LinearLayout.LayoutParams(dp(112), WRAP_CONTENT))
        addView(TextView(context, null, 0, R.style.Zwana_Value), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun signIn() {
        val user = username.text?.toString()?.trim().orEmpty()
        val pass = password.text?.toString().orEmpty()
        if (user.isEmpty() || pass.isEmpty()) {
            account.text = getString(R.string.missing_login)
            return
        }
        vault.setCredentials(Credentials(user, pass))
        password.text?.clear()
        editingLogin = false
        store.note("account", "login saved for $user")
        readingSince = store.notes()["read"]
        readingStarted = System.currentTimeMillis()
        Work.refresh(this, force = true, trigger = "sign-in")
        render()
    }

    /** Straight from this screen rather than through the worker, so the two paths can be told apart. */
    private fun checkWatch() {
        garminLines = listOf(getString(R.string.checking))
        render()
        background.execute {
            val lines = try {
                Garmin.check(applicationContext)
            } catch (e: Exception) {
                listOf("check failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            }
            store.log("check: ${lines.joinToString("; ")}")
            main.post {
                garminLines = lines
                render()
            }
        }
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
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private fun <T : View> view(id: Int) = lazy(LazyThreadSafetyMode.NONE) { findViewById<T>(id) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val JOURNAL_LINES = 40
        const val READ_TIMEOUT_MS = 45_000L
    }
}
