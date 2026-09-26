package io.github.gsfernandes81.zwanaquota

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.gsfernandes81.zwanaquota.core.Face
import io.github.gsfernandes81.zwanaquota.core.Level

/**
 * The home-screen widget. Works with nothing else installed: no Termux, no
 * Garmin Connect, no watch.
 *
 * It is redrawn from the cache the moment anything asks -- the system's
 * half-hourly update, a tap -- and then again when the read behind it
 * finishes. A tap reads the portal; signed out, a tap opens the sign-in
 * screen instead, since that is the only thing a tap could usefully do.
 */
class QuotaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        draw(context, Refresher.cachedFace(context), busy = false)
        Work.refresh(context, force = false, trigger = "widget update")
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TAP) {
            draw(context, Refresher.cachedFace(context), busy = true)
            Work.refresh(context, force = true, trigger = "tap")
        }
    }

    companion object {
        private const val ACTION_TAP = "io.github.gsfernandes81.zwanaquota.TAP"

        // Catppuccin Mocha, as the Termux faces are.
        private const val TEXT = 0xFFCDD6F4.toInt()
        private const val SUBTLE = 0xFFA6ADC8.toInt()
        private const val WARN = 0xFFFAB387.toInt()
        private val LEVEL = mapOf(
            Level.OK to 0xFFA6E3A1.toInt(),
            Level.LOW to 0xFFF9E2AF.toInt(),
            Level.CRITICAL to 0xFFF38BA8.toInt(),
            Level.UNKNOWN to 0xFFBAC2DE.toInt(),
        )

        /** Draw [face] on every placed copy of the widget. */
        fun draw(context: Context, face: Face, busy: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, QuotaWidget::class.java)
            if (manager.getAppWidgetIds(component).isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.figure, face.figure)
            views.setTextColor(R.id.figure, LEVEL.getValue(face.level))
            views.setTextViewText(R.id.status, face.status)
            views.setTextColor(R.id.status, if (face.warning) WARN else TEXT)
            views.setTextViewText(R.id.reset, face.reset)
            views.setTextColor(R.id.reset, TEXT)
            views.setTextViewText(R.id.footnote, if (busy) "reading the portal..." else face.footnote)
            views.setTextColor(R.id.footnote, SUBTLE)
            views.setOnClickPendingIntent(R.id.root, tapIntent(context))
            manager.updateAppWidget(component, views)
        }

        private fun tapIntent(context: Context): PendingIntent =
            if (Vault(context).signedIn) {
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
