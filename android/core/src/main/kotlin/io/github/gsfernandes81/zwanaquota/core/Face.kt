package io.github.gsfernandes81.zwanaquota.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How much is left, as a word: what the colour follows and the watch is sent. */
enum class Level(val word: String) {
    OK("ok"),
    LOW("low"),
    CRITICAL("critical"),
    UNKNOWN("unknown"),
}

/**
 * quota_widget.py's spellings. Numbers are rounded exactly as Python's format
 * rounds them -- the exact binary value, half to even -- so a figure reads the
 * same on the widget as in Termux.
 */
object Format {
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** Compact byte count: no decimals below a GiB, two above. */
    fun size(bytes: Long): String = size(bytes.toDouble())

    fun size(bytes: Double): String {
        var value = bytes
        for (unit in listOf("B", "KiB", "MiB")) {
            if (Math.abs(value) < 1024) return "${fixed(value, 0)} $unit"
            value /= 1024
        }
        return "${fixed(value, 2)} GiB"
    }

    /** How old a reading is. */
    fun since(seconds: Double): String = when {
        seconds < 90 -> "${fixed(seconds, 0)}s ago"
        seconds < 5400 -> "${fixed(seconds / 60, 0)}m ago"
        else -> "${fixed(seconds / 3600, 0)}h ago"
    }

    fun percent(share: Double): String = "${fixed(share * 100, 0)}%"

    /** A credit is a dollar. Whole dollars lose the pointless `.00`. */
    fun money(credits: Double): String =
        if (credits == Math.floor(credits)) "\$${fixed(credits, 0)}" else "\$${fixed(credits, 2)}"

    fun clock(at: Instant, zone: ZoneId): String = clockFormat.format(at.atZone(zone))

    /** Comfortable, thin, nearly gone -- quota_widget.grade(). */
    fun grade(share: Double): Level = when {
        share >= 0.25 -> Level.OK
        share >= 0.08 -> Level.LOW
        else -> Level.CRITICAL
    }

    /** `f"{value:,.{places}f}"`. */
    fun fixed(value: Double, places: Int): String {
        val exact = BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN)
        val pattern = if (places == 0) "#,##0" else "#,##0." + "0".repeat(places)
        return DecimalFormat(pattern, DecimalFormatSymbols(Locale.ROOT)).format(exact)
    }
}

/**
 * The widget's face as four lines of text, before anything draws them.
 *
 * The rules live here rather than in the Android code so the JVM tests reach
 * them. Three are the ones CLAUDE.md holds the Termux faces to:
 *
 * - **The reset survives.** It has a line of its own, so nothing a wide figure
 *   or a long warning does can push it off; it is the one thing on the face
 *   that cannot be inferred from the rest.
 * - **A reading that overstates what is left says so**, on the line that
 *   would otherwise carry the share -- it outranks the share.
 * - **Nothing relative is drawn that could go stale.** A home-screen widget is
 *   a picture, redrawn only when something asks: "3h 20m" to the reset would
 *   still say 3h 20m an hour later. So the clock times are absolute (reset
 *   at 00:00, read at 14:02), and remain true however long the picture stays.
 */
data class Face(
    val figure: String,
    val status: String,
    val reset: String,
    val footnote: String,
    val level: Level,
    /** Free data left to spend right now, not only paid: the QS tile's `active`. */
    val freeNow: Boolean,
    /** The reading overstates, or there is none: draw it so it is noticed. */
    val warning: Boolean,
) {
    val lines: List<String> get() = listOf(figure, status, reset, footnote)

    companion object {
        /** Older than this and not live, a reading is drawn as its age. */
        const val STALE_AFTER_SECONDS = 90.0

        fun of(doc: Document, zone: ZoneId): Face {
            val share = doc.remainderBytes.toDouble() / maxOf(1L, doc.poolBytes)
            val percent = Format.percent(share)
            val mark = mark(doc)
            val resetAt = Format.clock(doc.reset, zone)
            return Face(
                figure = Format.size(doc.remainderBytes),
                status = if (mark != null) "$mark, $percent" else "$percent of ${Format.size(doc.poolBytes)}",
                reset = if (doc.grantBytes > 0) "+${Format.size(doc.grantBytes)} at $resetAt" else "resets $resetAt",
                footnote = "read ${Format.clock(doc.readingTaken, zone)}, ${Format.money(doc.credits)} reserve",
                level = Format.grade(share),
                freeNow = doc.freeLeftBytes > 0,
                warning = mark != null,
            )
        }

        /** The Termux tile's `quota ? / no reading`, with why on the last line. */
        fun unknown(now: Instant, zone: ZoneId, why: String): Face = Face(
            figure = "quota ?",
            status = "no reading",
            reset = "resets ${Format.clock(Pipeline.nextReset(now), zone)}",
            footnote = why,
            level = Level.UNKNOWN,
            freeNow = false,
            warning = true,
        )

        /** Why a reading overstates, if it does: its age, or the session being down. */
        fun mark(doc: Document): String? = when {
            !doc.live && doc.ageSeconds >= STALE_AFTER_SECONDS -> Format.since(doc.ageSeconds)
            !doc.online -> "offline"
            else -> null
        }
    }
}

/**
 * What the phone sends the watch: one place, so the wire contract is spelled
 * once and tested once. The Monkey C side (garmin/source/Quota.mc) reads
 * these keys and nothing else.
 *
 * The Connect IQ SDK carries only Integer, Float, String, Boolean, List and
 * HashMap -- no Long -- so byte counts go as whole KiB, and times as epoch
 * seconds in an Int (which lasts until 2038). The figures go as the strings
 * the phone's own face draws: the watch holds no unit ladder, no thresholds
 * and no grades, so there is no third copy of any of them to drift.
 *
 * The watch works out staleness itself, from `ts`: it never trusts `live`,
 * which was true when the phone sent it and says nothing about now.
 */
object WatchPayload {
    const val VERSION = 1

    fun build(doc: Document, face: Face, now: Instant, sequence: Int, everySeconds: Int): HashMap<String, Any> =
        hashMapOf(
            "v" to VERSION,
            "n" to sequence,
            "ts" to epochInt(doc.readingTaken),
            "sent" to epochInt(now),
            "every" to everySeconds,
            "reset" to epochInt(doc.reset),
            "rem" to kib(doc.remainderBytes),
            "pool" to kib(doc.poolBytes),
            "grant" to kib(doc.grantBytes),
            "free" to kib(doc.freeLeftBytes),
            "online" to doc.online,
            "live" to doc.live,
            "fig" to face.figure,
            "share" to Format.percent(doc.remainderBytes.toDouble() / maxOf(1L, doc.poolBytes)),
            "gfig" to Format.size(doc.grantBytes),
            "level" to face.level.word,
        )

    private fun kib(bytes: Long): Int = (maxOf(0L, bytes) / 1024).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun epochInt(t: Instant): Int = t.epochSecond.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
