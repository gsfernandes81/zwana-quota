package io.github.gsfernandes81.zwanaquota.core

import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tests/test_derive.py's properties, over a seeded spread of readings.
 *
 * The vectors hold the port to the Python's numbers at fixed points; these
 * hold it to the Python's *guarantees* everywhere between them -- above all
 * that free.left is an upper bound and paid.left a lower one, which is what
 * anything gating a download on this figure stands on.
 */
class DeriveTest {
    private val now = Instant.parse("2026-09-02T20:30:00Z")
    private val grant = 763L * 1024 * 1024

    private fun reading(
        remainder: Long,
        grant: Long,
        drawn: Long,
        pool: Long = maxOf(grant + drawn, remainder),
        first: Double = Pipeline.epochSeconds(now),
        credits: Double = 3.0,
        perCredit: Long = PortalClient.BYTES_PER_CREDIT,
        online: Boolean = true,
    ) = Reading(
        Pipeline.epochSeconds(now), credits, perCredit, remainder, 0, grant, drawn,
        online, "", Pipeline.utcDate(now), pool, first,
    )

    /** The Hypothesis strategy in test_derive.py, drawn from a fixed seed. */
    private fun readings(seed: Int, count: Int = 2000) = Random(seed).let { rng ->
        List(count) {
            val r = reading(
                remainder = rng.nextLong(0, 1L shl 42),
                grant = rng.nextLong(0, 1L shl 32),
                drawn = rng.nextLong(0, 1L shl 40),
                credits = rng.nextDouble(0.0, 10_000.0),
                perCredit = rng.nextLong(1, 1L shl 32),
                online = rng.nextBoolean(),
            )
            if (rng.nextBoolean()) r.copy(pool = r.pool + rng.nextLong(0, 1L shl 40)) else r
        }
    }

    private fun derive(r: Reading) = Pipeline.derive(r, 0.0, true, now)

    @Test
    fun `the two halves of the remainder add up to it, and neither goes negative`() {
        for (r in readings(1)) {
            val d = derive(r)
            assertEquals(d.remainderBytes, d.freeLeftBytes + d.paidLeftBytes, "$r")
            assertTrue(d.freeLeftBytes >= 0 && d.paidLeftBytes >= 0, "$r")
            assertTrue(d.freeLeftBytes <= d.grantBytes, "$r")
            assertTrue(d.poolBytes >= 1 && d.todayUsedBytes >= 0 && d.freeUsedBytes >= 0, "$r")
            assertTrue(d.carryInBytes >= 0, "$r")
        }
    }

    @Test
    fun `hidden carry-in can only lower free and raise paid`() {
        val rng = Random(2)
        for (r in readings(3)) {
            val hidden = rng.nextLong(0, 1L shl 42)
            val seen = derive(r)
            val truth = derive(r.copy(pool = r.pool + hidden))
            assertTrue(truth.freeLeftBytes <= seen.freeLeftBytes, "$r + $hidden")
            assertTrue(truth.paidLeftBytes >= seen.paidLeftBytes, "$r + $hidden")
        }
    }

    @Test
    fun `more observed drawing never makes free data appear`() {
        val rng = Random(4)
        for (r in readings(5)) {
            val extra = rng.nextLong(0, 1L shl 40)
            assertTrue(derive(r.copy(drawnToday = r.drawnToday + extra)).freeLeftBytes <= derive(r).freeLeftBytes)
        }
    }

    @Test
    fun `carry-in is what the pool cannot explain any other way`() {
        for (r in readings(6)) {
            val d = derive(r)
            assertEquals(maxOf(0L, d.poolBytes - r.grant - r.drawnToday), d.carryInBytes)
            assertEquals(d.carryInBytes > 0, d.carryInObserved)
        }
    }

    @Test
    fun `a first reading within five minutes of the reset saw the whole day`() {
        val lastReset = Pipeline.epochSeconds(Pipeline.nextReset(now).minus(Duration.ofDays(1)))
        for (gap in listOf(-10_000.0, -1.0, 0.0, 1.0, 299.0, 300.0, 300.5, 301.0, 4 * 3600.0)) {
            val d = derive(reading(grant, grant, 0, first = lastReset + gap))
            assertEquals(maxOf(0.0, gap) > 300, d.freeLeftIsUpperBound, "gap $gap")
        }
    }

    @Test
    fun `the reset is the next midnight UTC, strictly after now`() {
        val midnight = Instant.parse("2026-09-03T00:00:00Z")
        for (t in listOf("2026-09-02T00:00:01Z", "2026-09-02T12:00:00Z", "2026-09-02T23:59:59Z")) {
            assertEquals(midnight, Pipeline.nextReset(Instant.parse(t)), t)
        }
        assertEquals(midnight.plus(Duration.ofDays(1)), Pipeline.nextReset(midnight))
    }

    @Test
    fun `the pool is a high-water mark that never survives the reset`() {
        val today = Carry(Pipeline.utcDate(now), 10 * grant, 1.0, grant, 0)
        assertEquals(10 * grant, Pipeline.dayPool(grant, grant, 0, today, now).pool)
        assertEquals(1.0, Pipeline.dayPool(grant, grant, 0, today, now).poolFirstTs)
        val yesterday = today.copy(poolDay = "2026-09-01")
        assertEquals(grant, Pipeline.dayPool(grant, grant, 0, yesterday, now).pool)
        assertEquals(Pipeline.epochSeconds(now), Pipeline.dayPool(grant, grant, 0, yesterday, now).poolFirstTs)
        // The remainder is a hard floor on the pool.
        assertEquals(5 * grant, Pipeline.dayPool(5 * grant, grant, 0, null, now).pool)
    }

    @Test
    fun `a reading survives its own JSON, and a broken one is no reading`() {
        val r = reading(grant, grant, 40)
        assertEquals(r, Reading.fromJson(r.toJson()))
        assertEquals(null, Reading.fromJson(null))
        assertEquals(null, Reading.fromJson(kotlinx.serialization.json.JsonPrimitive("x")))
        assertEquals(null, Reading.fromJson(kotlinx.serialization.json.buildJsonObject {
            put("ts", kotlinx.serialization.json.JsonPrimitive(1.0))
        }))
    }
}
