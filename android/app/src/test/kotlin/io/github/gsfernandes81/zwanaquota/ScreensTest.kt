package io.github.gsfernandes81.zwanaquota

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.github.gsfernandes81.zwanaquota.core.Pipeline
import io.github.gsfernandes81.zwanaquota.core.Reading
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
