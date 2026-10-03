package io.github.gsfernandes81.zwanaquota

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.github.gsfernandes81.zwanaquota.core.Pipeline
import io.github.gsfernandes81.zwanaquota.core.Reading
import io.github.gsfernandes81.zwanaquota.core.Session
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Draws the app's screen to PNGs under build/screens, so it can be looked at
 * without a phone: CI prints them into the job log. Robolectric's native
 * graphics draw real pixels from the real resources.
 *
 * It pins no wording and no layout (CLAUDE.md: behaviour, not output). All it
 * asserts is that the screen draws, which is still worth something: a layout
 * that inflates badly or a theme attribute that does not resolve fails here,
 * not on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1700dp-xhdpi")
class ScreensTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val grant = 763L * 1024 * 1024

    private companion object {
        const val ME = "10.1.0.225"
        const val LAPTOP = "10.1.0.125"
        const val TABLET = "10.1.0.31"
        val MACS = mapOf(LAPTOP to "aa:bb:cc:00:00:01", TABLET to "aa:bb:cc:00:00:02", "10.1.0.40" to "aa:bb:cc:00:00:03")
    }

    @Test
    fun `a fresh reading, signed out, light`() {
        seed(remainder = 1_804_000_000, ageSeconds = 20, notes = false)
        shoot("light-fresh", expand = false)
    }

    @Test
    fun `a stale low reading with the diagnostics open, dark`() {
        RuntimeEnvironment.setQualifiers("+night")
        seed(remainder = 190_000_000, ageSeconds = 2 * 3600, notes = true)
        shoot("dark-stale", expand = true)
    }

    /**
     * The widget, compact and resized large, in each state of the data
     * switch: this phone switched data on with a laptop joined, this phone
     * joined someone else's, data on elsewhere without this phone, data off.
     */
    @Test
    fun `the widget in each state of the data switch`() {
        seed(remainder = 316_000_000, ageSeconds = 20, notes = false)
        val me = "10.1.0.225"
        val laptop = "10.1.0.125"
        Store(context).saveNames(mapOf(laptop to Store.Named("gavins-thinkpad", Instant.now().epochSecond)))
        val sessions = listOf(
            Session(true, me, 0, me, listOf(laptop)),
            Session(true, laptop, 0, me, listOf(me, "10.1.0.31", "10.1.0.40")),
            Session(true, laptop, 0, me, emptyList()),
            Session(false, null, 0, me, emptyList()),
        )
        val density = context.resources.displayMetrics.density
        val sizes = listOf(false to (250 to 150), true to (330 to 250))
        val gap = (16 * density).toInt()
        val width = gap + sizes.sumOf { (it.second.first * density).toInt() + gap }
        val rowHeight = (sizes.maxOf { it.second.second } * density).toInt() + gap
        val sheet = Bitmap.createBitmap(width, gap + sessions.size * rowHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF22324A.toInt())
        sessions.forEachIndexed { row, session ->
            Store(context).save(session)
            var x = gap
            for ((large, size) in sizes) {
                val w = (size.first * density).toInt()
                val h = (size.second * density).toInt()
                val parent = android.widget.FrameLayout(context)
                val view = QuotaWidget.remoteViews(context, Refresher.cachedFace(context), null, large, signedIn = true)
                    .apply(context, parent)
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
                canvas.save()
                canvas.translate(x.toFloat(), (gap + row * rowHeight).toFloat())
                view.draw(canvas)
                canvas.restore()
                x += w + gap
            }
        }
        val out = File("build/screens/widget.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("nothing was written", out.length() > 0)
    }

    /**
     * The panel the Quick Settings tile opens, as its dialog draws it: this
     * phone switched data on with three others; the question before one is
     * taken off; and data off. Also writes what the tile itself says at both
     * widths beside it, since the tile is drawn by the system, not here.
     */
    @Test
    fun `the tile's panel in each state, light`() {
        val tiles = StringBuilder()
        for (name in listOf("primary", "confirm", "off")) {
            seed(remainder = 1_804_000_000, ageSeconds = 20, notes = false)
            Store(context).save(if (name == "off") Session(false, null, 0, ME, emptyList()) else Session(true, ME, 0, ME, listOf(LAPTOP, TABLET, "10.1.0.40"), MACS))
            val panel = panel()
            if (name == "confirm") {
                val rows = panel.view.findViewById<android.widget.LinearLayout>(R.id.devices)
                rows.getChildAt(1).findViewById<View>(R.id.device_off).performClick()
            }
            shootPanel(panel.view, "panel-$name")
            tiles.append(tileLines(name))
        }
        val out = File("build/screens/tile.txt").apply { parentFile?.mkdirs() }
        out.writeText(tiles.toString())
        assertTrue("nothing was written", out.length() > 0)
    }

    /** This phone joined a laptop's session, with a reading two hours old, dark. */
    @Test
    fun `the tile's panel joined to another's session with an old reading, dark`() {
        RuntimeEnvironment.setQualifiers("+night")
        seed(remainder = 190_000_000, ageSeconds = 2 * 3600, notes = false)
        Store(context).save(Session(true, LAPTOP, 0, ME, listOf(ME, TABLET), MACS))
        shootPanel(panel().view, "panel-joined")
        File("build/screens/tile-joined.txt").writeText(tileLines("joined"))
    }

    /** The panel as the tile builds it, signed in, in a window so the meter draws (it draws only attached). */
    private fun panel(): TilePanel {
        Store(context).saveNames(
            mapOf(LAPTOP to Store.Named("gavins-thinkpad", Instant.now().epochSecond), TABLET to Store.Named("galaxy-tab-s9", Instant.now().epochSecond)),
        )
        val window = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val panel = TilePanel(
            android.view.ContextThemeWrapper(window, R.style.Theme_Zwana),
            object : TilePanel.Host {
                override fun refresh() {}
                override fun openApp() {}
                override fun openPortal() {}
                override fun close() {}
            },
        )
        panel.render(Refresher.cachedFace(context), null, signedIn = true)
        window.setContentView(panel.view)
        return panel
    }

    private fun tileLines(name: String): String = buildString {
        for (width in io.github.gsfernandes81.zwanaquota.core.TileWidth.entries) {
            Store(context).tileWidth = width
            val tile = QuotaTile.tileFace(context)
            appendLine("$name $width: ${tile.label} | ${tile.subtitle} | ${tile.icon} | active=${tile.active}")
        }
    }

    /** [view] on a dialog's surface, its rounded corners and its width on a phone. */
    private fun shootPanel(view: View, name: String) {
        val density = context.resources.displayMetrics.density
        val w = (340 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, w, view.measuredHeight)
        val gap = (16 * density).toInt()
        val sheet = Bitmap.createBitmap(w + 2 * gap, view.measuredHeight + 2 * gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF22324A.toInt())
        val surface = android.util.TypedValue().also { view.context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, it, true) }.data
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = surface }
        canvas.drawRoundRect(gap.toFloat(), gap.toFloat(), (gap + w).toFloat(), (gap + view.measuredHeight).toFloat(), 28 * density, 28 * density, paint)
        canvas.save()
        canvas.translate(gap.toFloat(), gap.toFloat())
        view.draw(canvas)
        canvas.restore()
        val out = File("build/screens/$name.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("nothing was written", out.length() > 0)
    }

    /**
     * The launcher icon as launchers draw it: the adaptive layers under a
     * circle and a rounded-square mask, and the themed (monochrome) layer
     * Android 13+ tints, side by side on a neutral ground.
     */
    @Test
    fun `the launcher icon under the common masks`() {
        val size = 288
        val gap = 48
        val sheet = Bitmap.createBitmap(gap + 3 * (size + gap), size + 2 * gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFFE6E6E6.toInt())
        val icon = context.getDrawable(R.drawable.ic_launcher) as android.graphics.drawable.AdaptiveIconDrawable
        val masks = listOf(
            android.graphics.Path().apply { addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW) },
            android.graphics.Path().apply {
                addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * 0.3f, size * 0.3f, android.graphics.Path.Direction.CW)
            },
        )
        masks.forEachIndexed { i, mask -> drawLayers(canvas, icon, mask, gap + i * (size + gap), gap, size, themed = false) }
        drawLayers(canvas, icon, masks[0], gap + 2 * (size + gap), gap, size, themed = true)
        val out = File("build/screens/icon.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("nothing was written", out.length() > 0)
    }

    /** The adaptive icon's 108dp layers, scaled so the 72dp viewport fills [size], clipped to [mask]. */
    private fun drawLayers(
        canvas: Canvas,
        icon: android.graphics.drawable.AdaptiveIconDrawable,
        mask: android.graphics.Path,
        x: Int,
        y: Int,
        size: Int,
        themed: Boolean,
    ) {
        val full = (size * 108f / 72f).toInt()
        val inset = (full - size) / 2
        canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        canvas.clipPath(mask)
        if (themed) {
            canvas.drawColor(0xFFD7E3FF.toInt())
            icon.monochrome?.mutate()?.apply {
                setBounds(-inset, -inset, full - inset, full - inset)
                setTint(0xFF1A3A6B.toInt())
                draw(canvas)
            }
        } else {
            for (layer in listOfNotNull(icon.background, icon.foreground)) {
                layer.setBounds(-inset, -inset, full - inset, full - inset)
                layer.draw(canvas)
            }
        }
        canvas.restore()
    }

    private fun seed(remainder: Long, ageSeconds: Long, notes: Boolean) {
        val now = Instant.now()
        val ts = Pipeline.epochSeconds(now) - ageSeconds
        Store(context).save(
            Reading(
                ts = ts, credits = 3.0, perCredit = 419_430_400, remainder = remainder, allocated = 0,
                grant = grant, drawnToday = 1_073_741_824, online = true, profile = "nightly",
                poolDay = Pipeline.utcDate(now), pool = grant + 1_073_741_824, poolFirstTs = ts,
            ),
        )
        if (notes) {
            val store = Store(context)
            store.note("read", "ok over Wi-Fi (tap)")
            store.note("watch", "Instinct 3 Solar: sent")
            store.note("worker", "ran (every 30m)")
        }
    }

    private fun shoot(name: String, expand: Boolean) {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        if (expand) activity.findViewById<View>(R.id.diagnostics_header).performClick()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        assertTrue("the screen has no size", root.width > 0 && root.height > 0)

        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val out = File("build/screens/$name.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("nothing was written", out.length() > 0)
    }
}
