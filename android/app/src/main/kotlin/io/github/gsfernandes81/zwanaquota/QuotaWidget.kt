package io.github.gsfernandes81.zwanaquota

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import io.github.gsfernandes81.zwanaquota.core.Device
import io.github.gsfernandes81.zwanaquota.core.Face
import io.github.gsfernandes81.zwanaquota.core.Level
import io.github.gsfernandes81.zwanaquota.core.Role
import io.github.gsfernandes81.zwanaquota.core.Session
import io.github.gsfernandes81.zwanaquota.core.SessionAction

/**
 * The home-screen widget. Works with nothing else installed: no Termux, no
 * Garmin Connect, no watch.
 *
 * It is redrawn from the cache the moment anything asks -- the system's
 * half-hourly update, a tap -- and then again when the read behind it
 * finishes. A tap reads the portal; signed out, a tap opens the sign-in
 * screen instead, since that is the only thing a tap could usefully do.
 *
 * Beside the figure is the data switch, once the portal's session has been
 * read: "Data on" when this phone is on it, "Data off", or "Join" when data
 * is on for another device and not this one. Anything that takes a device
 * off asks first ([ConfirmActivity]); switching on and joining do not.
 */
class QuotaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        draw(context, Refresher.cachedFace(context))
        Work.refresh(context, force = false, trigger = "widget update")
        Work.schedule(context)
    }

    /** The first widget placed, or the last removed: start or stop keeping it fresh. */
    override fun onEnabled(context: Context) = Work.schedule(context)

    override fun onDisabled(context: Context) = Work.schedule(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TAP -> {
                Faces.draw(context, Refresher.cachedFace(context), context.getString(R.string.busy_read))
                Work.refresh(context, force = true, trigger = "tap")
            }
            ACTION_SWITCH -> actionOf(intent)?.let { switch(context, it) }
        }
    }

    companion object {
        private const val ACTION_TAP = "io.github.gsfernandes81.zwanaquota.TAP"
        private const val ACTION_SWITCH = "io.github.gsfernandes81.zwanaquota.SWITCH"
        const val EXTRA_ACTION = "action"

        // Catppuccin Mocha, as the Termux faces are.
        private const val TEXT = 0xFFCDD6F4.toInt()
        private const val SUBTLE = 0xFFA6ADC8.toInt()
        private const val WARN = 0xFFFAB387.toInt()
        private const val GREEN = 0xFFA6E3A1.toInt()
        private const val AMBER = 0xFFF9E2AF.toInt()
        private const val GREY = 0xFFBAC2DE.toInt()
        private val LEVEL = mapOf(
            Level.OK to GREEN,
            Level.LOW to AMBER,
            Level.CRITICAL to 0xFFF38BA8.toInt(),
            Level.UNKNOWN to GREY,
        )

        /** The device rows the large face has; one more device than fits makes the last "+N more". */
        private val DEVICE_ROWS = listOf(R.id.device1, R.id.device2, R.id.device3)

        /** Whether any copy of the widget is on a home screen. */
        fun placed(context: Context): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, QuotaWidget::class.java)).isNotEmpty()

        fun actionOf(intent: Intent): SessionAction? =
            intent.getStringExtra(EXTRA_ACTION)?.let { name -> SessionAction.entries.firstOrNull { it.name == name } }

        /** Throw the switch, the confirmation already given if it needed one. */
        fun switch(context: Context, action: SessionAction, trigger: String = "widget switch") {
            Faces.draw(context, Refresher.cachedFace(context), context.getString(busy(action)))
            Work.switch(context, action, trigger)
        }

        /**
         * Draw [face] on every placed copy of the widget, with [busy] in place
         * of the footnote while something is on its way.
         */
        fun draw(context: Context, face: Face, busy: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, QuotaWidget::class.java)
            if (manager.getAppWidgetIds(component).isEmpty()) return

            val signedIn = Vault(context).signedIn
            val compact = remoteViews(context, face, busy, large = false, signedIn)
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android picks the largest that fits the widget's size.
                RemoteViews(
                    mapOf(
                        SizeF(110f, 80f) to compact,
                        SizeF(180f, 200f) to remoteViews(context, face, busy, large = true, signedIn),
                    ),
                )
            } else {
                compact
            }
            manager.updateAppWidget(component, views)
        }

        /** One of the two faces, as drawn now: the compact one, or the [large] one with the device list. */
        internal fun remoteViews(context: Context, face: Face, busy: String?, large: Boolean, signedIn: Boolean): RemoteViews {
            val store = Store(context)
            // Signed out, the session on file is nobody's to switch.
            val session = store.session()?.takeIf { signedIn }
            val names = store.names().filterValues { it.name.isNotEmpty() }.mapValues { it.value.name }
            val views = RemoteViews(context.packageName, if (large) R.layout.widget_large else R.layout.widget)
            views.setTextViewText(R.id.figure, face.figure)
            views.setTextColor(R.id.figure, LEVEL.getValue(face.level))
            views.setTextViewText(R.id.status, face.status)
            views.setTextColor(R.id.status, if (face.warning) WARN else TEXT)
            views.setViewVisibility(R.id.paid, if (face.paid.isEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.paid, face.paid)
            views.setTextViewText(R.id.reset, face.reset)
            views.setTextColor(R.id.reset, TEXT)
            views.setTextViewText(R.id.footnote, busy ?: face.footnote)
            views.setTextColor(R.id.footnote, SUBTLE)
            views.setOnClickPendingIntent(R.id.root, tapIntent(context, signedIn))
            pill(context, views, session)
            if (large) deviceRows(context, views, session, names) else deviceLine(views, session)
            return views
        }

        private fun pill(context: Context, views: RemoteViews, session: Session?) {
            val action = session?.action
            if (session == null || action == null) {
                views.setViewVisibility(R.id.pill, View.GONE)
                return
            }
            val (text, colour, background) = when (session.role) {
                Role.OFF -> Triple(R.string.pill_off, GREY, R.drawable.pill_off)
                Role.OUTSIDE -> Triple(R.string.pill_join, AMBER, R.drawable.pill_join)
                else -> Triple(R.string.pill_on, GREEN, R.drawable.pill_on)
            }
            views.setViewVisibility(R.id.pill, View.VISIBLE)
            views.setTextViewText(R.id.pill, context.getString(text))
            views.setTextColor(R.id.pill, colour)
            views.setInt(R.id.pill, "setBackgroundResource", background)
            views.setOnClickPendingIntent(R.id.pill, switchIntent(context, action))
        }

        /** The compact face's one line: who is on. */
        private fun deviceLine(views: RemoteViews, session: Session?) {
            if (session == null) {
                views.setViewVisibility(R.id.devices, View.GONE)
                return
            }
            views.setViewVisibility(R.id.devices, View.VISIBLE)
            views.setTextViewText(R.id.devices, dotted(session.summary(), if (session.on) GREEN else GREY))
            views.setTextColor(R.id.devices, TEXT)
        }

        /** The large face's list: a row per device, this phone first, the last row "+N more" when they do not fit. */
        private fun deviceRows(context: Context, views: RemoteViews, session: Session?, names: Map<String, String>) {
            val devices: List<Device> = session?.devices(names).orEmpty()
            val rows: List<CharSequence> = when {
                session == null -> emptyList()
                devices.isEmpty() -> listOf(dotted(session.summary(), GREY))
                devices.size <= DEVICE_ROWS.size -> devices.map { dotted(it.label, GREEN) }
                else -> devices.take(DEVICE_ROWS.size - 1).map { dotted(it.label, GREEN) } +
                    context.getString(R.string.more_devices, devices.size - (DEVICE_ROWS.size - 1))
            }
            views.setViewVisibility(R.id.rule, if (rows.isEmpty()) View.GONE else View.VISIBLE)
            DEVICE_ROWS.forEachIndexed { i, id ->
                val row = rows.getOrNull(i)
                views.setViewVisibility(id, if (row == null) View.GONE else View.VISIBLE)
                if (row != null) {
                    views.setTextViewText(id, row)
                    views.setTextColor(id, if (row is String) SUBTLE else TEXT)
                }
            }
        }

        /** "● text", the dot in [colour] and the text in the line's own. */
        private fun dotted(text: String, colour: Int): CharSequence =
            SpannableStringBuilder("●  ").apply {
                setSpan(ForegroundColorSpan(colour), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(text)
            }

        private fun busy(action: SessionAction) = when (action) {
            SessionAction.TURN_ON -> R.string.busy_turn_on
            SessionAction.TURN_OFF_EVERYWHERE -> R.string.busy_turn_off_everywhere
            SessionAction.LEAVE -> R.string.busy_leave
            SessionAction.JOIN -> R.string.busy_join
        }

        /**
         * What tapping the switch does: [action] straight away, or, for one
         * that takes a device off, the question first. Each action has its
         * own request code, so one pending intent never stands in for another.
         */
        private fun switchIntent(context: Context, action: SessionAction): PendingIntent {
            val code = 10 + action.ordinal
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (action.confirm) {
                PendingIntent.getActivity(
                    context,
                    code,
                    Intent(context, ConfirmActivity::class.java)
                        .putExtra(EXTRA_ACTION, action.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    flags,
                )
            } else {
                PendingIntent.getBroadcast(
                    context,
                    code,
                    Intent(context, QuotaWidget::class.java).setAction(ACTION_SWITCH).putExtra(EXTRA_ACTION, action.name),
                    flags,
                )
            }
        }

        private fun tapIntent(context: Context, signedIn: Boolean): PendingIntent =
            if (signedIn) {
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, QuotaWidget::class.java).setAction(ACTION_TAP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
    }
}
