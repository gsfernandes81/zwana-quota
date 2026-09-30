package io.github.gsfernandes81.zwanaquota.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The spellings by round trip, and the face by its rules -- never by its
 * wording, which is free to change (CLAUDE.md).
 */
class FaceTest {
    private val now = Instant.parse("2026-09-02T20:30:00Z")
    private val grant = 763L * 1024 * 1024

    /** Six zones either side of the date line, as test_format.py sweeps them. */
    private val zones = listOf("UTC", "Pacific/Kiritimati", "Pacific/Pago_Pago", "Asia/Kolkata", "America/St_Johns", "Asia/Manila")
        .map { ZoneId.of(it) }

    private val units = mapOf("B" to 1.0, "KiB" to 1024.0, "MiB" to 1024.0.pow(2), "GiB" to 1024.0.pow(3))

    /** A spelling read back, and half its last printed digit: how far out it may be. */
    private fun parse(text: String): Pair<Double, Double> {
        val (number, unit) = text.split(' ')
        val digits = number.replace(",", "")
        val places = digits.substringAfter('.', "").length
        val scale = units.getValue(unit)
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

    @Test
    fun `a size is true to the precision it printed`() {
        val rng = Random(7)
        val named = listOf(0L, 1, 1023, 1024, 1025, 1024L * 1024 - 1, 1024L * 1024, 1024L * 1024 * 1024 - 1,
            1024L * 1024 * 1024, 1_207_959_552L /* 1.125 GiB: a tie */, 32L shl 40)
        for (bytes in named + List(2000) { rng.nextLong(0, 1L shl 45) }) {
            val (value, tolerance) = parse(Format.size(bytes))
            assertTrue(Math.abs(value - bytes) <= tolerance + 1e-6 * bytes, "${Format.size(bytes)} for $bytes")
        }
    }

    @Test
    fun `sizes round half to even, as Python's format does`() {
        // 1.125 GiB is exactly representable; Python prints 1.12, and so must this.
        assertEquals("1.12 GiB", Format.size(1_207_959_552L))
        assertEquals("1,023 B", Format.size(1023L))
    }

    @Test
    fun `a paid figure is whole MiB at every size, true to the precision it printed`() {
        val rng = Random(11)
        val mib = 1024L * 1024
        val named = listOf(0L, 1, mib / 2, mib / 2 + 1, mib - 1, mib, 1023 * mib, 1024 * mib, 5L shl 30, 32L shl 40)
        for (bytes in named + List(2000) { rng.nextLong(0, 1L shl 45) }) {
            val text = Format.mib(bytes)
            assertTrue(text.endsWith(" MiB"), text)
            val (value, tolerance) = parse(text)
            assertEquals(0.5 * mib, tolerance, text)
            assertTrue(Math.abs(value - bytes) <= tolerance + 1e-9 * bytes, "$text for $bytes")
        }
    }

    @Test
    fun `the paid figure is the paid part of what is left, and absent with no reading`() {
        val carried = 2L shl 30
        for ((remainder, pool) in listOf(grant to grant, grant / 2 + carried to grant + carried, carried to grant + carried, 0L to grant)) {
            val d = doc(remainder, pool = pool)
            val face = Face.of(d, ZoneId.of("UTC"))
            assertTrue(Format.mib(d.paidLeftBytes) in face.paid, "${face.paid} for $d")
            assertFalse('\n' in face.paid)
            assertEquals(Format.mib(d.paidLeftBytes), WatchPayload.build(d, face, now, 1, 1800)["paid"])
        }
        assertEquals("", Face.unknown(now, ZoneId.of("UTC"), "sign in").paid)
    }

    @Test
    fun `an age is true to its unit`() {
        for (s in listOf(0.0, 1.0, 89.4, 90.0, 600.0, 5399.0, 5400.0, 86_400.0 * 3)) {
            val text = Format.since(s)
            val n = text.takeWhile { it.isDigit() || it == ',' }.replace(",", "").toDouble()
            val unit = when (text.removePrefix(text.takeWhile { it.isDigit() || it == ',' })[0]) {
                's' -> 1.0; 'm' -> 60.0; 'h' -> 3600.0; else -> error(text)
            }
            assertTrue(Math.abs(n * unit - s) <= unit / 2 + 1e-9, "$text for $s")
        }
    }

    @Test
    fun `the three grades, at their edges`() {
        assertEquals(Level.OK, Format.grade(0.25))
        assertEquals(Level.LOW, Format.grade(0.2499))
        assertEquals(Level.LOW, Format.grade(0.08))
        assertEquals(Level.CRITICAL, Format.grade(0.0799))
        assertEquals(Level.CRITICAL, Format.grade(0.0))
    }

    @Test
    fun `the reset time is on every face, in every zone, on either clock`() {
        val docs = listOf(doc(), doc(age = 7200.0, live = false), doc(online = false), doc(0), doc(32L shl 40), doc(grantBytes = 0))
        for (zone in zones) {
            for (hour24 in listOf(true, false)) {
                val at = Format.resetClock(Pipeline.nextReset(now), zone, hour24)
                for (d in docs) {
                    val reset = Face.of(d, zone, hour24).reset
                    // Last on its line, which the widget ellipsizes from the
                    // start: the one end an ellipsis cannot reach.
                    assertTrue(reset.endsWith(at), "$zone $reset")
                }
                assertTrue(Face.unknown(now, zone, "why", hour24).reset.endsWith(at))
            }
        }
    }

    @Test
    fun `the two clocks name the same minute`() {
        val zone = ZoneId.of("UTC")
        for (minute in listOf(0, 1, 59, 60, 719, 720, 721, 1439)) {
            val at = Instant.ofEpochSecond(minute * 60L)
            val (h, m) = Format.clock(at, zone, true).split(':').map { it.toInt() }
            val twelve = Format.clock(at, zone, false)
            val match = Regex("""(\d{1,2}):(\d{2}) (am|pm)""").matchEntire(twelve)!!
            val h12 = match.groupValues[1].toInt()
            assertTrue(h12 in 1..12, twelve)
            assertEquals(m, match.groupValues[2].toInt())
            assertEquals(h, h12 % 12 + if (match.groupValues[3] == "pm") 12 else 0, twelve)
        }
    }

    @Test
    fun `a stale or offline reading is drawn as one, and a live one is not`() {
        val zone = ZoneId.of("UTC")
        val stale = Face.of(doc(age = 7200.0, live = false), zone)
        assertTrue(stale.warning)
        assertTrue(Format.since(7200.0) in stale.status)
        val offline = Face.of(doc(online = false), zone)
        assertTrue(offline.warning)
        assertNotNull(Face.mark(doc(online = false)))
        val live = Face.of(doc(), zone)
        assertFalse(live.warning)
        // Young enough not to be worth saying, however it was read.
        assertFalse(Face.of(doc(age = 30.0, live = false), zone).warning)
    }

    @Test
    fun `the reading's own time is on the face, so a picture left standing cannot pass for current`() {
        for (zone in zones) {
            val d = doc(age = 7200.0, live = false)
            assertTrue(Format.clock(d.readingTaken, zone) in Face.of(d, zone).footnote)
        }
    }

    @Test
    fun `the figure is the remainder, and no line is ever blank`() {
        for (remainder in listOf(0L, 1, 1023, grant, 5L shl 30, 32L shl 40)) {
            val face = Face.of(doc(remainder), ZoneId.of("UTC"))
            assertEquals(Format.size(remainder), face.figure)
            assertTrue(face.lines.all { it.isNotBlank() }, "$face")
            assertTrue(face.lines.none { '\n' in it }, "$face")
        }
        assertTrue(Face.unknown(now, ZoneId.of("UTC"), "sign in").lines.all { it.isNotBlank() })
    }

    @Test
    fun `free-now follows free data, not the remainder`() {
        val carried = 2L shl 30
        assertTrue(Face.of(doc(grant), ZoneId.of("UTC")).freeNow)
        assertFalse(Face.of(doc(carried, pool = grant + carried), ZoneId.of("UTC")).freeNow)
    }

    @Test
    fun `the watch is told whether it may ask, and is never told yes by default`() {
        val d = doc()
        val face = Face.of(d, ZoneId.of("UTC"))
        assertEquals(false, WatchPayload.build(d, face, now, 1, 1800)["ask"])
        assertEquals(true, WatchPayload.build(d, face, now, 1, 1800, canAsk = true)["ask"])
    }

    @Test
    fun `the watch is sent only the types the Connect IQ SDK can carry`() {
        for (remainder in listOf(0L, grant, 1L shl 42, Long.MAX_VALUE / 2)) {
            val d = doc(remainder)
            val payload = WatchPayload.build(d, Face.of(d, ZoneId.of("UTC")), now, 7, 1800)
            for ((k, v) in payload) {
                assertTrue(v is Int || v is Boolean || v is String, "$k is ${v::class}")
                if (v is Int) assertTrue(v >= 0, "$k = $v")
            }
            assertEquals(Pipeline.nextReset(now).epochSecond.toInt(), payload["reset"])
            assertEquals(Format.size(remainder), payload["fig"])
        }
    }

    @Test
    fun `the payload's keys are the wire contract the watch reads`() {
        val d = doc()
        val keys = WatchPayload.build(d, Face.of(d, ZoneId.of("UTC")), now, 1, 1800).keys
        // garmin/source/Quota.mc reads these; renaming one silently blanks the glance.
        assertTrue(keys.containsAll(listOf("v", "n", "ts", "every", "reset", "online", "fig", "share", "gfig", "paid", "level")))
    }
}
