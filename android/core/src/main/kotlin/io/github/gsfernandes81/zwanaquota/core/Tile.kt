package io.github.gsfernandes81.zwanaquota.core

import java.time.ZoneId

/**
 * How wide the Quick Settings tile is, as character budgets for its two
 * lines. Android never tells a tile its size -- not on stock Android, and
 * not on One UI 8.5, where a tile can be dragged wider -- so it is a setting.
 *
 * There is no one-cell budget: a one-cell tile draws no text at all, only the
 * icon, which is why the icon carries the level and the warning ([TileIcon]).
 * The budgets are quota_widget.QS_SIZES' `medium` and `large`, measured on
 * this phone's panel for the Tasker tile this one replaces.
 */
enum class TileWidth(val label: Int, val subtitle: Int) {
    /** Two cells: the tile every panel draws with text. */
    STANDARD(10, 16),

    /** Dragged wider than two cells, which is told the pool as well. */
    WIDE(12, 34),
}

/** What the tile's icon shows: the level, unless the figure cannot be stood behind. */
enum class TileIcon { OK, LOW, CRITICAL, UNKNOWN, STALE, OFFLINE }

/**
 * The Quick Settings tile's face: quota_widget.compose_qs, which the Tasker
 * tile printed, on the phone's own clock.
 *
 * - **The reset survives.** The subtitle is a ladder, and every rung of a
 *   current reading keeps the reset time, the one thing on the tile that
 *   cannot be worked out from the rest.
 * - **A reading that overstates says so**, in place of the share, at every
 *   width -- and on the icon, which is all a one-cell tile draws.
 * - **The ladder is climbed as well as descended**: a wide tile is told the
 *   pool, rather than left saying what a narrow one says.
 * - **[active] is never a third state.** Android's unavailable state greys a
 *   tile out and stops it being tapped, and a tap is how a tile with no
 *   reading gets one.
 */
data class TileFace(
    val label: String,
    val subtitle: String,
    /** Free data left to spend right now: the tile is drawn lit. Paid only, or no reading: dim. */
    val active: Boolean,
    val icon: TileIcon,
    /** The whole of it in words, for TalkBack: never clipped. */
    val description: String,
) {
    companion object {
        fun of(doc: Document, zone: ZoneId, hour24: Boolean, width: TileWidth): TileFace {
            val pool = maxOf(1L, doc.poolBytes)
            val share = doc.remainderBytes.toDouble() / pool
            val percent = Format.percent(share)
            val stamp = Format.clock(doc.reset, zone, hour24)
            val mark = Face.mark(doc)
            val ladder = if (mark != null) {
                listOf("$mark, $percent of ${Format.size(pool)}, $stamp", "$mark, $percent, $stamp", "$mark, $stamp", mark)
            } else {
                val grant = size(doc.grantBytes, 8)
                val granted = if (doc.grantBytes > 0) {
                    listOf("$percent of ${Format.size(pool)}, +$grant $stamp", "$percent, +$grant at $stamp", "$percent, +$grant $stamp")
                } else {
                    listOf("$percent of ${Format.size(pool)}, reset $stamp")
                }
                granted + listOf("$percent, reset $stamp", "$percent, $stamp", stamp)
            }
            val level = Format.grade(share)
            return TileFace(
                label = size(doc.remainderBytes, width.label),
                subtitle = ladder.firstOrNull { it.length <= width.subtitle } ?: ladder.last(),
                active = doc.freeLeftBytes > 0,
                icon = when {
                    mark == null -> when (level) {
                        Level.OK -> TileIcon.OK
                        Level.LOW -> TileIcon.LOW
                        else -> TileIcon.CRITICAL
                    }
                    // The same precedence as the mark: age first.
                    mark == "offline" -> TileIcon.OFFLINE
                    else -> TileIcon.STALE
                },
                description = "${Format.size(doc.remainderBytes)} left: ${ladder.first()}",
            )
        }

        /** The Tasker tile's `quota ? / no reading`: [why] in the subtitle, and still tappable. */
        fun unknown(why: String): TileFace = TileFace(
            label = "quota ?",
            subtitle = why,
            active = false,
            icon = TileIcon.UNKNOWN,
            description = "no reading: $why",
        )

        /**
         * The remainder in as much precision as [room] characters allow:
         * quota_widget.qs_size. The first spelling is [Format.size]'s, so an
         * unclipped tile reads exactly as the widget does; the rungs below
         * give up decimals, then the space and the thousands separator.
         */
        fun size(bytes: Long, room: Int): String {
            val gib = bytes / 1073741824.0
            val options = when {
                gib >= 1 -> listOf(
                    "${Format.fixed(gib, 2)} GiB",
                    "${Format.fixed(gib, 1)} GiB",
                    "${Format.fixed(gib, 0)} GiB",
                    "${plain(gib)}G",
                )
                bytes >= 1048576 -> (bytes / 1048576.0).let { listOf("${Format.fixed(it, 0)} MiB", "${plain(it)}M") }
                bytes >= 1024 -> (bytes / 1024.0).let { listOf("${Format.fixed(it, 0)} KiB", "${plain(it)}K") }
                else -> listOf("${Format.fixed(bytes.toDouble(), 0)} B", "${plain(bytes.toDouble())}B")
            }
            return options.firstOrNull { it.length <= room } ?: options.last()
        }

        private fun plain(value: Double): String = Format.fixed(value, 0).replace(",", "")
    }
}
