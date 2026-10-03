package io.github.gsfernandes81.zwanaquota

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.ContextThemeWrapper
import androidx.core.net.toUri
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.gsfernandes81.zwanaquota.core.Face
import io.github.gsfernandes81.zwanaquota.core.Pipeline
import io.github.gsfernandes81.zwanaquota.core.PortalClient
import io.github.gsfernandes81.zwanaquota.core.TileFace
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.ZoneId

/**
 * The quota in the Quick Settings panel: the figure as the label, the share
 * and the reset as the subtitle (on a tile two cells wide or more; [TileFace]
 * has the ladder). Lit while this phone is on data and dim otherwise
 * ([TileFace.lit]); the icon is the same satellite whatever the state or the
 * reading, so the tile is always found in the same place by the same mark.
 *
 * A tap opens [TilePanel] over the panel with TileService.showDialog(), the
 * way Android lets a tile open up without leaving for an app: the reading,
 * the data switch, and each device with its own way off. Signed out, a tap
 * opens the sign-in screen instead. On a locked phone the panel waits for the
 * unlock, since it can take devices off. A long press opens the app
 * (QS_TILE_PREFERENCES, in the manifest).
 *
 * The tile is drawn whenever the panel shows it, from the cache, and a read
 * is started then if the cache is older than [Refresher.MAX_AGE_SECONDS] --
 * as the Tasker tile it replaced did. Nothing keeps it fresh in between, because
 * nothing needs to: a tile is only seen when the panel is pulled down.
 *
 * Never Tile.STATE_UNAVAILABLE: it greys the tile out and stops it being
 * tapped, and a tap is how a tile with no reading gets one.
 */
class QuotaTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        listening = WeakReference(this)
        redraw()
        val reading = Store(this).reading()
        val now = Instant.now()
        val old = reading == null || Pipeline.epochSeconds(now) - reading.ts > Refresher.MAX_AGE_SECONDS
        // Not again within the same span: a portal that does not answer would
        // otherwise be asked every time the panel is pulled down.
        if (Vault(this).signedIn && old && now.epochSecond - lastShownRead > Refresher.MAX_AGE_SECONDS) {
            lastShownRead = now.epochSecond
            Work.refresh(this, force = false, trigger = "tile shown")
        }
    }

    override fun onStopListening() {
        if (listening?.get() === this) listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!Vault(this).signedIn) return open(Intent(this, SettingsActivity::class.java), APP)
        if (isLocked) unlockAndRun { openPanel() } else openPanel()
    }

    /** The panel, as a dialog over the Quick Settings panel, and a fresh read behind it. */
    private fun openPanel() {
        val themed = DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(this, R.style.Theme_Zwana))
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val panel = TilePanel(
            themed,
            object : TilePanel.Host {
                override fun refresh() = read()

                override fun openApp() {
                    dialog.dismiss()
                    open(Intent(this@QuotaTile, SettingsActivity::class.java), APP)
                }

                override fun openPortal() {
                    dialog.dismiss()
                    open(Intent(Intent.ACTION_VIEW, PortalClient.PORTAL_URL.toUri()), PORTAL)
                }

                override fun close() = dialog.dismiss()
            },
        )
        dialog = MaterialAlertDialogBuilder(themed).setView(panel.view).create()
        dialog.setOnDismissListener { if (shown?.get() === panel) shown = null }
        shown = WeakReference(panel)
        panel.render(Refresher.cachedFace(this), null)
        showDialog(dialog)
        read()
    }

    private fun read() {
        Faces.draw(this, Refresher.cachedFace(this), getString(R.string.busy_read))
        Work.refresh(this, force = true, trigger = "tile")
    }

    /** The tile from the cache, as of now. */
    private fun redraw() {
        val tile = qsTile ?: return
        val face = tileFace(this)
        val lit = TileFace.lit(Store(this).session()?.takeIf { Vault(this).signedIn })
        tile.label = face.label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = face.subtitle
        val spoken = getString(if (lit) R.string.tile_spoken_on else R.string.tile_spoken_off, face.description)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = spoken
        tile.contentDescription = spoken
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.state = if (lit) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    /**
     * Start [intent] and fold the panel away. A tile may start an activity
     * only this way from Android 14; [code] keeps each one's pending intent
     * its own. Before 14 the Intent form is the only one there is, which is
     * what lint is told.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent, code: Int) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val APP = 0
        private const val PORTAL = 1
        private val main = Handler(Looper.getMainLooper())

        /** The tile while the panel shows it, and the panel while it is open: what [draw] reaches. */
        @Volatile private var listening: WeakReference<QuotaTile>? = null

        @Volatile private var shown: WeakReference<TilePanel>? = null

        /** When the panel being shown last started a read, epoch seconds. */
        @Volatile private var lastShownRead = 0L

        /**
         * Draw the tile and the open panel again, if either is showing. Only
         * what is showing: an app cannot redraw a tile the panel is not
         * showing, and does not need to, since showing it draws it.
         */
        fun draw(face: Face, busy: String?) {
            main.post {
                listening?.get()?.redraw()
                shown?.get()?.render(face, busy)
            }
        }

        /** The tile's face from the cache, as of now, at the width it was set to. */
        fun tileFace(context: Context): TileFace {
            val now = Instant.now()
            val reading = Store(context).reading()
                ?: return TileFace.unknown(context.getString(if (Vault(context).signedIn) R.string.tile_no_reading else R.string.tile_sign_in))
            val doc = Pipeline.derive(reading, Pipeline.epochSeconds(now) - reading.ts, false, now)
            return TileFace.of(doc, ZoneId.systemDefault(), Refresher.hour24(context), Store(context).tileWidth)
        }
    }
}
