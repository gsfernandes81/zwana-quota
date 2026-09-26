package io.github.gsfernandes81.zwanaquota.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The reading and the derivation: quota_widget.py's gather(), day_pool(),
 * derive() and next_reset(), ported rule for rule.
 *
 * The Python is the truth. This copy exists because the app has to work on a
 * phone with nothing else installed, and it is held to the Python by
 * vectors/quota.json -- numbers written by the live Python, which the tests in
 * this module must reproduce exactly. Change a rule there, run `make vectors`,
 * and this suite fails until the same change is made here.
 *
 * Unlike the Python, nothing here reads a clock: `now` is always passed in,
 * which is what lets the vectors be replayed without patching anything.
 */
object Pipeline {
    /**
     * `CreditHistory.TopUpMethod` for the nightly grant. Those entries cost
     * nothing and are never a paid draw (README, "The portal API").
     */
    const val FREE_TOPUP_METHOD = 4.0

    /**
     * The hour, UTC, the allowance is topped up. The README observes the grant
     * landing at 00:02:19; `next_reset` targets 00:00:00, and this matches the
     * Python rather than the observation, so the phone's two faces never
     * disagree by two minutes. Fix both together or neither.
     */
    const val RESET_HOUR_UTC = 0

    /** Five minutes: a first reading this close to the reset saw the whole day. */
    private const val OBSERVED_FROM_RESET_SECONDS = 300.0

    /** `balance.get("Balance")` must be a number, or nothing below means anything. */
    fun checkBalance(balance: JsonElement?) {
        if (balance.obj()?.get("Balance").number() == null) {
            throw PortalError("balance response has no numeric Balance field")
        }
    }

    /** `Remainder` is the only exact statement of what is left. */
    fun checkActive(active: JsonElement?) {
        if (active.obj()?.get("Remainder").number() == null) {
            throw PortalError("active provider response has no numeric Remainder")
        }
    }

    /**
     * The portal's three answers as one raw reading.
     *
     * [history] is null when the history call failed. That is not the same as
     * an empty history: losing it keeps today's grant from [previous] rather
     * than reading as a zero grant, which would say there is no free data left
     * -- wrong, and wrong in the dangerous direction.
     */
    fun gather(
        balance: JsonElement?,
        active: JsonElement?,
        history: JsonElement?,
        previous: Carry?,
        now: Instant,
    ): Reading {
        checkBalance(balance)
        checkActive(active)
        val b = balance.obj()!!
        val a = active.obj()!!
        val remainder = a["Remainder"].number()!!.truncate()

        var perCredit = PortalClient.BYTES_PER_CREDIT
        var grant = 0L
        var drawnToday = 0L
        val today = utcDate(now)

        // `history is None` in the Python covers both a failed call and one
        // that answered with an empty body.
        val lost = history == null || history is JsonNull
        if (lost && previous != null && previous.poolDay == today) {
            grant = previous.grant
            drawnToday = previous.drawnToday
        }

        // Sorted by the date as a string, stably, as `sorted(key=str(...))`.
        val rows = history.list()?.map { it.obj() }?.sortedBy { it?.get("Date").pyStr() }
        for (row in rows ?: emptyList()) {
            if (row == null) continue
            val provider = row["UserProvider"].obj()?.get("Provider").obj()
            val unitCost = provider?.get("UnitCost").number()?.toDouble()
            if (provider?.get("IsByteType").truthy() && unitCost != null && unitCost > 0) {
                // Python's round() is half-to-even; so is rint.
                perCredit = Math.rint(1 / unitCost).toLong()
            }

            val amount = row["Allocation"].number() ?: continue
            val free = row["CreditHistory"].obj()?.get("TopUpMethod").number()
                ?.toDouble() == FREE_TOPUP_METHOD
            if (free) {
                // The grant has been the same every day on record, but it is
                // read rather than hard-coded.
                grant = amount.truncate()
            }
            // Timestamps are UTC and unzoned; a date-prefix compare is enough.
            if (row["Date"].pyStr().startsWith(today) && !free) {
                drawnToday += amount.truncate()
            }
        }

        val pool = dayPool(remainder, grant, drawnToday, previous, now)
        return Reading(
            ts = epochSeconds(now),
            credits = b["Balance"].number()!!.toDouble(),
            perCredit = perCredit,
            remainder = remainder,
            allocated = a["Allocated"]?.takeIf { it.truthy() }.number()?.truncate() ?: 0,
            grant = grant,
            drawnToday = drawnToday,
            online = b["Online"].truthy(),
            profile = b["CronProfileName"]?.takeIf { it.truthy() }?.pyStr() ?: "",
            poolDay = pool.poolDay!!,
            pool = pool.pool,
            poolFirstTs = pool.poolFirstTs!!,
        )
    }

    /**
     * Today's whole pool: the 100% the share is drawn against.
     *
     * The grant is spent first and expires at the reset; paid data carries
     * over and is spent last. So the pool is the grant, plus today's draws,
     * plus whatever paid data survived midnight -- and nothing in the API
     * states that last term. `Remainder` can never exceed the pool, so it is a
     * floor; the pool only grows during a day, so the largest floor seen so far
     * today still holds, carried forward in the cache.
     */
    fun dayPool(remainder: Long, grant: Long, drawnToday: Long, previous: Carry?, now: Instant): Carry {
        val today = utcDate(now)
        val nowTs = epochSeconds(now)
        var floor = grant + drawnToday
        var firstTs = nowTs
        if (previous != null && previous.poolDay == today) {
            floor = maxOf(floor, previous.pool)
            firstTs = previous.poolFirstTs?.takeIf { it != 0.0 } ?: nowTs
        }
        return Carry(today, maxOf(floor, remainder), firstTs, grant, drawnToday)
    }

    /** The next top-up, strictly after [now]. */
    fun nextReset(now: Instant): Instant {
        val utc = now.atZone(ZoneOffset.UTC)
        val today = ZonedDateTime.of(utc.toLocalDate(), LocalTime.of(RESET_HOUR_UTC, 0), ZoneOffset.UTC)
        return (if (today.isAfter(utc)) today else today.plusDays(1)).toInstant()
    }

    /**
     * The documented figures, and which way each can be wrong.
     *
     * `free.left` is an **upper bound**, never a floor: every term that can be
     * wrong -- unobserved carried-over paid data, the portal's accounting lag,
     * a stale reading -- pushes it the same way. `paid.left` is a lower bound.
     */
    fun derive(raw: Reading, age: Double, live: Boolean, now: Instant): Document {
        val reset = nextReset(now)
        val pool = maxOf(1L, raw.pool)
        val grant = raw.grant
        val drawn = raw.drawnToday
        val remainder = raw.remainder

        // Paid data that survived midnight. Nothing states it, but the pool's
        // high-water mark bounds it from below.
        val carryIn = maxOf(0L, pool - grant - drawn)
        val paidPool = carryIn + drawn

        // Grant first, paid last: free is what the remainder holds beyond the
        // paid pool, and never more than the grant.
        val freeLeft = maxOf(0L, minOf(grant, remainder - paidPool))
        val paidLeft = remainder - freeLeft

        // Usage between the reset and the day's first reading is invisible,
        // which is what makes free.left an upper bound.
        val lastReset = reset.minus(Duration.ofDays(1))
        val firstGap = maxOf(0.0, raw.poolFirstTs - epochSeconds(lastReset))
        val observedFromReset = firstGap <= OBSERVED_FROM_RESET_SECONDS

        return Document(
            readingTaken = instantOf(raw.ts),
            ageSeconds = age,
            live = live,
            online = raw.online,
            grantBytes = grant,
            freeLeftBytes = freeLeft,
            freeUsedBytes = maxOf(0L, grant - freeLeft),
            paidLeftBytes = paidLeft,
            drawnTodayBytes = drawn,
            carryInBytes = carryIn,
            poolBytes = pool,
            remainderBytes = remainder,
            todayUsedBytes = maxOf(0L, pool - remainder),
            credits = raw.credits,
            bytesPerCredit = raw.perCredit,
            reserveBytes = (raw.credits * raw.perCredit.toDouble()).toLong(),
            reset = reset,
            secondsUntilReset = Duration.between(now, reset).seconds,
            allocatedBytes = raw.allocated,
            freeLeftIsUpperBound = !observedFromReset,
            firstReadingAfterResetSeconds = firstGap.toLong(),
            carryInObserved = carryIn > 0,
        )
    }

    fun utcDate(now: Instant): String = now.atZone(ZoneOffset.UTC).toLocalDate().toString()

    fun epochSeconds(t: Instant): Double = t.epochSecond + t.nano / 1e9

    fun instantOf(seconds: Double): Instant {
        val whole = Math.floor(seconds).toLong()
        return Instant.ofEpochSecond(whole, ((seconds - whole) * 1e9).toLong())
    }
}

/** What [Pipeline.derive] documents. The fields of quota_widget.derive()'s JSON. */
data class Document(
    val readingTaken: Instant,
    val ageSeconds: Double,
    val live: Boolean,
    val online: Boolean,
    val grantBytes: Long,
    val freeLeftBytes: Long,
    val freeUsedBytes: Long,
    val paidLeftBytes: Long,
    val drawnTodayBytes: Long,
    val carryInBytes: Long,
    val poolBytes: Long,
    val remainderBytes: Long,
    val todayUsedBytes: Long,
    val credits: Double,
    val bytesPerCredit: Long,
    val reserveBytes: Long,
    val reset: Instant,
    val secondsUntilReset: Long,
    val allocatedBytes: Long,
    val freeLeftIsUpperBound: Boolean,
    val firstReadingAfterResetSeconds: Long,
    val carryInObserved: Boolean,
)
