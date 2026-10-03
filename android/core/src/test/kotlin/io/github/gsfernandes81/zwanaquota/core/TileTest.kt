package io.github.gsfernandes81.zwanaquota.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Quick Settings tile by its rules, as tests/test_qs_tile.py held the
 * Tasker tile to them: the budgets at every magnitude, the reset surviving,
 * a reading that overstates saying so -- never the wording.
 */
class TileTest {
    private val now = Instant.parse("2026-09-02T20:30:00Z")
    private val grant = 763L * 1024 * 1024

    /** Six zones either side of the date line, as test_format.py sweeps them. */
    private val zones = listOf("UTC", "Pacific/Kiritimati", "Pacific/Pago_Pago", "Asia/Kolkata", "America/St_Johns", "Asia/Manila")
        .map { ZoneId.of(it) }

    /** The magnitudes the label is calibrated for: above 8 TiB the standard budget runs out of rungs. */
    private val magnitudes = listOf(
        0L, 1, 1023, 1024, 1024L * 1024 - 1, 1024L * 1024, 10L * 1024 * 1024, grant,
        (1L shl 30) - 1, 1L shl 30, 1_803_886_264, 10L shl 30, 100L shl 30, 1L shl 40, 8L shl 40,
    )

    private val units = mapOf("B" to 1.0, "KiB" to 1024.0, "MiB" to 1024.0.pow(2), "GiB" to 1024.0.pow(3))

    /** A tile spelling read back -- `1.68 GiB` or `2G` -- and half its last printed digit. */
    private fun parse(text: String): Pair<Double, Double> {
        val (number, unit) = Regex("""([\d,.]+) ?([A-Za-z]+)""").matchEntire(text)!!.destructured
        val digits = number.replace(",", "")
        val places = digits.substringAfter('.', "").length
        val scale = units[unit] ?: units.entries.first { it.key.startsWith(unit) }.value
        return digits.toDouble() * scale to 0.5 * 10.0.pow(-places) * scale
    }

    private fun doc(
        remainder: Long = grant,
        pool: Long = maxOf(grant, remainder),
        age: Double = 0.0,
        live: Boolean = true,
        online: Boolean = true,
        grantBytes: Long = grant,
    ) = Pipeline.derive(
        Reading(Pipeline.epochSeconds(now) - age, 3.0, PortalClient.BYTES_PER_CREDIT, remainder, 0, grantBytes, 0,
            online, "", Pipeline.utcDate(now), pool, Pipeline.epochSeconds(now)),
        age, live, now,
    )

    private val readings = mapOf(
        "current" to { r: Long -> doc(r) },
        "stale" to { r: Long -> doc(r, age = 7200.0, live = false) },
        "offline" to { r: Long -> doc(r, online = false) },
    )

    @Test
    fun `the label never lies, whatever the room`() {
        val rng = Random(3)
        for (bytes in magnitudes + List(2000) { rng.nextLong(0, 1L shl 43) }) {
            for (room in 0..20) {
                val text = TileFace.size(bytes, room)
                val (value, tolerance) = parse(text)
                assertTrue(Math.abs(value - bytes) <= tolerance + 1e-6 * bytes, "$text for $bytes in $room")
            }
        }
    }

    @Test
    fun `the label fits unless no spelling would, and more room never buys a shorter one`() {
        val rng = Random(5)
        for (bytes in magnitudes + List(2000) { rng.nextLong(0, 1L shl 43) }) {
            val shortest = TileFace.size(bytes, 0)
            for (room in 0..20) {
                val text = TileFace.size(bytes, room)
                assertTrue(text.length <= room || text == shortest, "$text in $room")
                assertTrue(TileFace.size(bytes, room + 1).length >= text.length, "$bytes at $room")
            }
        }
    }

    @Test
    fun `an unclipped label reads exactly as the widget does`() {
        val rng = Random(9)
        for (bytes in magnitudes + List(500) { rng.nextLong(0, 1L shl 43) }) {
            assertEquals(Format.size(bytes), TileFace.size(bytes, 99))
        }
    }

    @Test
    fun `both lines fit both widths at every magnitude, current, stale or offline, on either clock`() {
        for (width in TileWidth.entries) {
            for ((kind, make) in readings) {
                for (bytes in magnitudes) {
                    for (zone in zones) {
                        for (hour24 in listOf(true, false)) {
                            val tile = TileFace.of(make(bytes), zone, hour24, width)
                            assertTrue(tile.label.length <= width.label, "$width $kind label ${tile.label}")
                            assertTrue(tile.subtitle.length <= width.subtitle, "$width $kind subtitle ${tile.subtitle}")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the reset time survives on every current tile, in every zone, on either clock`() {
        for (width in TileWidth.entries) {
            for (bytes in magnitudes) {
                for (zone in zones) {
                    for (hour24 in listOf(true, false)) {
                        for (grantBytes in listOf(0L, grant)) {
                            val d = doc(bytes, grantBytes = grantBytes)
                            val tile = TileFace.of(d, zone, hour24, width)
                            assertTrue(Format.clock(d.reset, zone, hour24) in tile.subtitle, "$width ${tile.subtitle}")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a reading that overstates says so on the subtitle and the icon, at every width`() {
        for (width in TileWidth.entries) {
            for (bytes in magnitudes) {
                val stale = TileFace.of(doc(bytes, age = 7200.0, live = false), ZoneId.of("UTC"), true, width)
                assertTrue("ago" in stale.subtitle, stale.subtitle)
                assertEquals(TileIcon.STALE, stale.icon)
                val offline = TileFace.of(doc(bytes, online = false), ZoneId.of("UTC"), true, width)
                assertTrue("offline" in offline.subtitle, offline.subtitle)
                assertEquals(TileIcon.OFFLINE, offline.icon)
                val current = TileFace.of(doc(bytes), ZoneId.of("UTC"), true, width)
                assertFalse("ago" in current.subtitle || "offline" in current.subtitle, current.subtitle)
                assertTrue(current.icon in listOf(TileIcon.OK, TileIcon.LOW, TileIcon.CRITICAL))
            }
        }
    }

    @Test
    fun `the subtitle starts saying so at the same age the face does`() {
        for ((age, said) in listOf(0.0 to false, 89.4 to false, Face.STALE_AFTER_SECONDS to true, 7200.0 to true)) {
            val tile = TileFace.of(doc(1_803_886_264, age = age, live = false), ZoneId.of("UTC"), true, TileWidth.STANDARD)
            assertEquals(said, "ago" in tile.subtitle, "$age: ${tile.subtitle}")
        }
    }

    @Test
    fun `a wider tile is never told less`() {
        for ((kind, make) in readings) {
            for (bytes in magnitudes) {
                val d = make(bytes)
                val standard = TileFace.of(d, ZoneId.of("Asia/Kolkata"), false, TileWidth.STANDARD)
                val wide = TileFace.of(d, ZoneId.of("Asia/Kolkata"), false, TileWidth.WIDE)
                assertTrue(wide.label.length >= standard.label.length, "$kind ${wide.label}")
                assertTrue(wide.subtitle.length >= standard.subtitle.length, "$kind ${wide.subtitle}")
            }
        }
    }

    @Test
    fun `the icon is the level the widget would colour it`() {
        for (bytes in magnitudes) {
            val d = doc(bytes)
            val expected = when (Format.grade(d.remainderBytes.toDouble() / maxOf(1L, d.poolBytes))) {
                Level.OK -> TileIcon.OK
                Level.LOW -> TileIcon.LOW
                else -> TileIcon.CRITICAL
            }
            assertEquals(expected, TileFace.of(d, ZoneId.of("UTC"), true, TileWidth.STANDARD).icon)
        }
    }

    @Test
    fun `lit means there is still free data, not merely data`() {
        val rng = Random(13)
        repeat(500) {
            val d = doc(rng.nextLong(0, 1L shl 33), grantBytes = rng.nextLong(0, 1L shl 32))
            assertEquals(d.freeLeftBytes > 0, TileFace.of(d, ZoneId.of("UTC"), true, TileWidth.STANDARD).active)
        }
    }

    @Test
    fun `the spoken description is never clipped -- the figure, the reset, and any warning`() {
        for ((kind, make) in readings) {
            val d = make(1_803_886_264)
            val tile = TileFace.of(d, ZoneId.of("UTC"), true, TileWidth.STANDARD)
            assertTrue(Format.size(d.remainderBytes) in tile.description, tile.description)
            assertTrue(Format.clock(d.reset, ZoneId.of("UTC"), true) in tile.description, tile.description)
            Face.mark(d)?.let { assertTrue(it in tile.description, "$kind: ${tile.description}") }
        }
    }

    @Test
    fun `no reading is still a tile that can be tapped, with its reason on it`() {
        val tile = TileFace.unknown("tap to sign in")
        assertTrue(tile.label.isNotBlank())
        assertEquals("tap to sign in", tile.subtitle)
        assertEquals(TileIcon.UNKNOWN, tile.icon)
    }
}
