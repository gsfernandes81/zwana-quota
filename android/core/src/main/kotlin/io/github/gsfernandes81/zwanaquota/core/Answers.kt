package io.github.gsfernandes81.zwanaquota.core

/**
 * The watch's asks, answered by their ids, so that "answered" on the watch
 * means this ask was answered -- not merely that some message came.
 *
 * The watch gives every ask an id (garmin/source/Ask.mc), and the phone sends
 * back, in every message, the ids of the last [KEEP] asks it answered and
 * what became of each ([WatchPayload]: `re`, `rw`). A data switch or a removal
 * is answered by its job, with "" for done or a word for why not, or by the
 * listener refusing it ([WatchWords.DENIED], [WatchWords.BUSY]), and by nothing else. A
 * request for a reading is answered by a reading taken after the phone heard
 * it, or by the phone's word on why there is none: [settle].
 *
 * One list for one watch: the ids are only unique to the watch that made
 * them, and a second watch on the same phone is not supported.
 */
object Answers {
    /** How many answers every message carries: more than a wearer asks between two messages. */
    const val KEEP = 16

    /** A request for a reading heard this long ago without a reading since is answered [WatchWords.TOO_LATE]. */
    const val PENDING_MILLIS = 120_000L

    /** What became of ask [id]: "" for done, else a word for the watch to show. */
    data class Answer(val id: Int, val word: String)

    /**
     * A request for a reading, heard at [heardAt] (epoch milliseconds: a read
     * begun in the same second, before it was heard, does not answer it), not
     * yet answered.
     */
    data class Pending(val id: Int, val heardAt: Long)

    /**
     * Which of [pending] a message made at [now] answers. One heard before
     * [readingTs] (epoch seconds, when the reading in the message began) has
     * its reading; else one heard before a read that failed at [tried] has
     * [why]; else one older than [PENDING_MILLIS] is "too late". Returns what
     * stays pending and what is answered, in order. [tried] and [now] are
     * epoch milliseconds, as [Pending.heardAt] is.
     */
    fun settle(pending: List<Pending>, readingTs: Double, tried: Long?, why: String?, now: Long): Pair<List<Pending>, List<Answer>> {
        val still = mutableListOf<Pending>()
        val answered = mutableListOf<Answer>()
        for (p in pending) {
            when {
                readingTs * 1000 >= p.heardAt -> answered += Answer(p.id, "")
                why != null && tried != null && tried >= p.heardAt -> answered += Answer(p.id, why)
                now - p.heardAt > PENDING_MILLIS -> answered += Answer(p.id, WatchWords.TOO_LATE)
                else -> still += p
            }
        }
        return still to answered
    }

    /** [answers] with [more] added, an id answered again keeping its newest word, the newest [KEEP]. */
    fun keep(answers: List<Answer>, more: List<Answer>): List<Answer> =
        (answers.filter { a -> more.none { it.id == a.id } } + more).takeLast(KEEP)
}

/**
 * What the watch is told when an ask was not done (`rw`, beside its id): it
 * shows the word in a toast, and an Instinct's toast shows a few characters
 * and cuts the rest, so each is at most [MAX] -- a word or two, capitalised, not a sentence
 * (tests/test_watch_contract.py holds these and the watch's own toasts to
 * it). The phone's own notes and footnotes say the long version.
 */
object WatchWords {
    const val MAX = 8

    /** Another ask of the same kind is on its way. */
    const val BUSY = "Busy"

    /** The watch may not switch: its setting is off on the phone. */
    const val DENIED = "Denied"

    /** Heard too long ago to act on, or no reading came in time. */
    const val TOO_LATE = "Too late"

    /** The session changed since the watch was sent it: nothing done. */
    const val STALE = "Stale"

    /** The portal refused the read or the change. */
    const val FAILED = "Failed"

    /** The phone has no login. */
    const val SIGN_IN = "Sign in"
}
