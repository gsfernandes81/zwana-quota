package io.github.gsfernandes81.zwanaquota

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.gsfernandes81.zwanaquota.core.Device
import io.github.gsfernandes81.zwanaquota.core.Face
import io.github.gsfernandes81.zwanaquota.core.Level
import io.github.gsfernandes81.zwanaquota.core.Pipeline
import io.github.gsfernandes81.zwanaquota.core.Role
import io.github.gsfernandes81.zwanaquota.core.Session
import io.github.gsfernandes81.zwanaquota.core.SessionAction
import io.github.gsfernandes81.zwanaquota.core.removable
import java.time.Instant

/**
 * What the Quick Settings tile opens: today's reading as the app's card has
 * it, the data switch, and a row per device on the session, each other device
 * with its own way off when this phone may take it off.
 *
 * A view and nothing else, so it can be drawn without a tile (ScreensTest);
 * [QuotaTile] puts it in the dialog TileService.showDialog() shows. It is
 * drawn again whenever the widget is ([Faces]), so a read, a switch or a
 * removal finishing shows here while it is open.
 *
 * Nothing is taken off without a question first, asked in the panel itself
 * (one strip under the list), and what the question is about is checked
 * against the portal again before anything is sent (PortalClient.remove,
 * apply): a "yes" to a list that has since changed does nothing.
 */
class TilePanel(private val context: Context, private val host: Host) {
    /** What the panel cannot do itself: it is a view, not the tile. */
    interface Host {
        fun refresh()
        fun openApp()
        fun openPortal()
        fun close()
    }

    /** The question being asked, if one is. */
    private sealed class Ask {
        data class Switch(val action: SessionAction) : Ask()
        data class Remove(val device: Device, val mac: String?) : Ask()
    }

    // The dialog's frame is its parent, which is not here yet.
    val view: View = LayoutInflater.from(context).inflate(R.layout.tile_panel, FrameLayout(context), false)
    private val store = Store(context)
    private val figure = view.findViewById<TextView>(R.id.figure)
    private val level = view.findViewById<TextView>(R.id.level)
    private val meter = view.findViewById<LinearProgressIndicator>(R.id.meter)
    private val status = view.findViewById<TextView>(R.id.status)
    private val reset = view.findViewById<TextView>(R.id.reset)
    private val sessionState = view.findViewById<TextView>(R.id.session_state)
    private val sessionDetail = view.findViewById<TextView>(R.id.session_detail)
    private val sessionSwitch = view.findViewById<MaterialButton>(R.id.session_switch)
    private val devices = view.findViewById<LinearLayout>(R.id.devices)
    private val confirm = view.findViewById<MaterialCardView>(R.id.confirm)
    private val confirmTitle = view.findViewById<TextView>(R.id.confirm_title)
    private val confirmBody = view.findViewById<TextView>(R.id.confirm_body)
    private val confirmYes = view.findViewById<MaterialButton>(R.id.confirm_yes)
    private val footnote = view.findViewById<TextView>(R.id.footnote)
    private val refresh = view.findViewById<MaterialButton>(R.id.refresh)

    private var asking: Ask? = null
    private var face: Face = Refresher.cachedFace(context)
    private var busy: String? = null
    private var signedIn = false

    init {
        refresh.setOnClickListener { host.refresh() }
        view.findViewById<View>(R.id.open_app).setOnClickListener { host.openApp() }
        view.findViewById<View>(R.id.open_portal).setOnClickListener { host.openPortal() }
        view.findViewById<View>(R.id.done).setOnClickListener { host.close() }
        view.findViewById<View>(R.id.confirm_no).setOnClickListener {
            asking = null
            render(face, busy, signedIn)
        }
        confirmYes.setOnClickListener { asking?.let(::go) }
    }

    /**
     * Everything, from [face] and what is stored; [busy] stands in for the
     * footnote while something is on its way. Signed out, the session on
     * file is nobody's to switch.
     */
    fun render(face: Face, busy: String?, signedIn: Boolean = Vault(context).signedIn) {
        this.face = face
        this.busy = busy
        this.signedIn = signedIn
        val now = Instant.now()
        val doc = store.reading()?.let { Pipeline.derive(it, Pipeline.epochSeconds(now) - it.ts, false, now) }

        figure.text = face.figure
        // The level, unless the reading cannot be stood behind: then why, in grey.
        val mark = doc?.let { Face.mark(it) }
        val (icon, label) = when {
            doc == null -> R.drawable.ic_unknown to context.getString(R.string.level_unknown)
            mark == "offline" -> R.drawable.ic_offline to context.getString(R.string.offline)
            mark != null -> R.drawable.ic_stale to context.getString(R.string.out_of_date, mark)
            face.level == Level.OK -> R.drawable.ic_ok to context.getString(R.string.level_ok)
            face.level == Level.LOW -> R.drawable.ic_low to context.getString(R.string.level_low)
            else -> R.drawable.ic_critical to context.getString(R.string.level_critical)
        }
        val colour = ContextCompat.getColor(context, if (doc == null || mark != null) R.color.status_unknown else colourOf(face.level))
        level.text = label
        level.setTextColor(colour)
        level.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(level, ColorStateList.valueOf(colour))

        val share = doc?.let { it.remainderBytes.toDouble() / maxOf(1L, it.poolBytes) } ?: 0.0
        // The bar keeps the level's colour when the reading is old: the chip
        // above says it is old, and the share is still what it was.
        val fill = ContextCompat.getColor(context, colourOf(face.level))
        meter.setProgressCompat((share.coerceIn(0.0, 1.0) * 1000).toInt(), false)
        meter.setIndicatorColor(fill)
        meter.trackColor = ColorUtils.setAlphaComponent(fill, 0x3D)
        status.text = if (face.paid.isEmpty()) face.status else "${face.status} · ${face.paid}"
        reset.text = face.reset
        footnote.text = busy ?: face.footnote

        val session = store.session()?.takeIf { signedIn }
        // A question about something that has since changed is not asked any more.
        asking = asking?.takeIf { ask ->
            when (ask) {
                is Ask.Switch -> session?.action == ask.action
                is Ask.Remove -> session?.removable(ask.device.ip) == true
            }
        }
        renderSession(session)
        renderDevices(session)
        renderQuestion()
    }

    private fun renderSession(session: Session?) {
        val action = session?.action
        sessionState.text = context.getString(
            when {
                session == null -> R.string.panel_session_unknown
                session.on -> R.string.panel_data_on
                else -> R.string.panel_data_off
            },
        )
        sessionDetail.text = context.getString(
            when (session?.role) {
                null -> R.string.panel_session_unread
                Role.OFF -> R.string.panel_role_off
                Role.PRIMARY -> R.string.panel_role_primary
                Role.JOINED -> R.string.panel_role_joined
                Role.OUTSIDE -> R.string.panel_role_outside
                Role.UNKNOWN -> R.string.panel_role_unknown
            },
        )
        sessionSwitch.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action == null) return
        sessionSwitch.text = context.getString(
            when (action) {
                SessionAction.TURN_ON -> R.string.panel_turn_on
                SessionAction.TURN_OFF_EVERYWHERE -> R.string.panel_turn_off
                SessionAction.LEAVE -> R.string.panel_leave
                SessionAction.JOIN -> R.string.panel_join
            },
        )
        sessionSwitch.isEnabled = busy == null
        sessionSwitch.setOnClickListener { if (action.confirm) ask(Ask.Switch(action)) else go(Ask.Switch(action)) }
    }

    private fun renderDevices(session: Session?) {
        val list = session?.devices(names()).orEmpty()
        devices.removeAllViews()
        for (device in list) devices.addView(deviceRow(session!!, device))
    }

    /** One device: this phone first, by name where the network gave one, its way off where it has one. */
    private fun deviceRow(session: Session, device: Device): View {
        val row = LayoutInflater.from(context).inflate(R.layout.tile_device, devices, false)
        val icon = row.findViewById<ImageView>(R.id.device_icon)
        icon.setImageResource(if (device.me) R.drawable.ic_phone else R.drawable.ic_devices)
        icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_good))
        row.findViewById<TextView>(R.id.device_name).text = device.label
        val how = if (device.ip == session.primaryIp) R.string.panel_device_primary else R.string.panel_device_joined
        row.findViewById<TextView>(R.id.device_detail).text = context.getString(R.string.panel_device_detail, device.ip, context.getString(how))
        // This phone's own way off is the switch above, which says what it
        // does; the device that switched data on goes off only with data.
        val off = row.findViewById<MaterialButton>(R.id.device_off)
        val removable = !device.me && session.removable(device.ip)
        off.visibility = if (removable) View.VISIBLE else View.GONE
        off.isEnabled = busy == null
        off.contentDescription = context.getString(R.string.panel_disconnect_named, device.label)
        off.setOnClickListener { ask(Ask.Remove(device, session.macs[device.ip])) }
        val asked = (asking as? Ask.Remove)?.device?.ip == device.ip
        row.setBackgroundColor(if (asked) ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.status_critical), 0x1F) else 0)
        return row
    }

    private fun renderQuestion() {
        val ask = asking
        confirm.visibility = if (ask == null) View.GONE else View.VISIBLE
        if (ask == null) return
        val others = store.session()?.devices(names())?.filterNot { it.me }?.map { it.label }.orEmpty()
        when (ask) {
            is Ask.Remove -> {
                confirmTitle.text = context.getString(R.string.panel_confirm_remove_title, ask.device.label)
                confirmBody.text = context.getString(R.string.panel_confirm_remove_body)
                confirmYes.text = context.getString(R.string.panel_disconnect)
            }
            is Ask.Switch -> if (ask.action == SessionAction.TURN_OFF_EVERYWHERE) {
                confirmTitle.text = context.getString(if (others.isEmpty()) R.string.confirm_off_alone_title else R.string.confirm_off_title)
                confirmBody.text = if (others.isEmpty()) {
                    context.getString(R.string.confirm_off_alone_body)
                } else {
                    context.getString(R.string.confirm_off_body, (listOf(Session.THIS_PHONE) + others).joinToString(", "))
                }
                confirmYes.text = context.getString(R.string.confirm_off_yes)
            } else {
                confirmTitle.text = context.getString(R.string.confirm_leave_title)
                confirmBody.text = context.getString(R.string.confirm_leave_body, others.joinToString(", ").ifEmpty { "the others" })
                confirmYes.text = context.getString(R.string.confirm_leave_yes)
            }
        }
        confirmYes.isEnabled = busy == null
        val red = ContextCompat.getColor(context, R.color.status_critical)
        confirmYes.backgroundTintList = ColorStateList.valueOf(red)
        confirmYes.setTextColor(0xFFFFFFFF.toInt())
    }

    private fun ask(ask: Ask) {
        asking = ask
        render(face, busy, signedIn)
    }

    /** Do it: the worker checks it against the portal again first. */
    private fun go(ask: Ask) {
        asking = null
        when (ask) {
            is Ask.Switch -> QuotaWidget.switch(context, ask.action, "tile")
            is Ask.Remove -> {
                Faces.draw(context, Refresher.cachedFace(context), context.getString(R.string.busy_remove, ask.device.label))
                Work.remove(context, ask.device.ip, ask.mac)
            }
        }
        render(face, busy, signedIn)
    }

    private fun names() = store.names().filterValues { it.name.isNotEmpty() }.mapValues { it.value.name }

    @ColorRes
    private fun colourOf(level: Level): Int = when (level) {
        Level.OK -> R.color.status_good
        Level.LOW -> R.color.status_warning
        Level.CRITICAL -> R.color.status_critical
        Level.UNKNOWN -> R.color.status_unknown
    }
}
