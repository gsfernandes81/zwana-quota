package io.github.gsfernandes81.zwanaquota.core

import java.time.ZoneId

/**
 * How wide the Quick Settings tile is, as character budgets for its two
 * lines. Android never tells a tile its size -- not on stock Android, and
 * not on One UI 8.5, where a tile can be dragged wider -- so it is a setting.
 *
 * There is no one-cell budget: a one-cell tile draws no text at all, only its
 * icon and whether it is lit. The budgets were measured on this phone's panel
 * for the Tasker tile this one replaced (its `medium` and `large`).
 */
enum class TileWidth(val label: Int, val subtitle: Int) {
    /** Two cells: the tile every panel draws with text. */
    STANDARD(10, 16),

    /** Dragged wider than two cells, which is told the pool as well. */
    WIDE(12, 34),
}

/**
 * The Quick Settings tile's text: the ladder the Tasker tile printed
 * (quota_widget.compose_qs, retired with it), on the phone's own clock.
 *
 * - **The reset survives.** The subtitle is a ladder, and every rung of a
 *   current reading keeps the reset time, the one thing on the tile that
 *   cannot be worked out from the rest.
 * - **A reading that overstates says so**, in place of the share, at every
 *   width.
 * - **The ladder is climbed as well as descended**: a wide tile is told the
 *   pool, rather than left saying what a narrow one says.
 *
 * The text is the reading and nothing else. Whether the tile is lit is the
 * session ([lit]), and its icon never changes.
 */
data class TileFace(
    val label: String,
    val subtitle: String,
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
            return TileFace(
                label = size(doc.remainderBytes, width.label),
                subtitle = ladder.firstOrNull { it.length <= width.subtitle } ?: ladder.last(),
                description = "${Format.size(doc.remainderBytes)} left: ${ladder.first()}",
            )
        }

        /** No reading at all: `quota ?`, with [why] in the subtitle. */
        fun unknown(why: String): TileFace = TileFace(
            label = "quota ?",
            subtitle = why,
            description = "no reading: $why",
        )

        /**
         * Whether the tile is drawn lit: this phone is on data, as the
         * widget's switch would say "Data on". Data off, data on only for
         * other devices, or a session never read (or nobody signed in to
         * read it): dim. Never Android's third, unavailable state, which
         * greys a tile out and stops the tap that opens the panel.
         */
        fun lit(session: Session?): Boolean = session != null && session.on && session.role != Role.OUTSIDE

        /**
         * The remainder in as much precision as [room] characters allow:
         * the Tasker tile's qs_size. The first spelling is [Format.size]'s, so an
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
