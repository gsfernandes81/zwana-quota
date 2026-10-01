package io.github.gsfernandes81.zwanaquota.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the watch's asks are answered by id. The rule under test: a request for
 * a reading is answered only by a reading begun after the phone heard it, or
 * by why there is none -- never by a reading that was already on its way.
 */
class AnswersTest {
    private val heard = 1_790_000_000_500L   // epoch ms: half a second into a second
    private val ask = Answers.Pending(7, heard)

    @Test
    fun `a reading begun after the ask was heard answers it, one begun before does not`() {
        val after = Answers.settle(listOf(ask), (heard + 1) / 1000.0, null, null, heard + 5000)
        assertEquals(emptyList<Answers.Pending>() to listOf(Answers.Answer(7, "")), after)
        // Begun earlier in the same second: already on its way, so not this ask's.
        val before = Answers.settle(listOf(ask), (heard - 100) / 1000.0, null, null, heard + 5000)
        assertEquals(listOf(ask) to emptyList<Answers.Answer>(), before)
    }

    @Test
    fun `a read that failed after the ask was heard answers it with why`() {
        val (still, answered) = Answers.settle(listOf(ask), (heard - 60_000) / 1000.0, heard + 10, "portal not reached", heard + 5000)
        assertEquals(emptyList(), still)
        assertEquals(listOf(Answers.Answer(7, "portal not reached")), answered)
        // A failure from before it was heard says nothing about it.
        assertEquals(listOf(ask), Answers.settle(listOf(ask), null, heard - 10, "portal not reached", heard + 5000).first)
    }

    @Test
    fun `an ask left without a reading is too late once the watch has stopped waiting`() {
        val old = Answers.settle(listOf(ask), null, null, null, heard + Answers.PENDING_MILLIS + 1)
        assertEquals(listOf(Answers.Answer(7, "too late")), old.second)
        assertEquals(listOf(ask), Answers.settle(listOf(ask), null, null, null, heard + Answers.PENDING_MILLIS).first)
    }

    @Test
    fun `answers keep the newest word per id, and only the newest few`() {
        val first = Answers.keep(emptyList(), listOf(Answers.Answer(1, "phone busy")))
        val again = Answers.keep(first, listOf(Answers.Answer(1, "")))
        assertEquals(listOf(Answers.Answer(1, "")), again)
        val many = (1..Answers.KEEP + 5).fold(emptyList<Answers.Answer>()) { kept, i -> Answers.keep(kept, listOf(Answers.Answer(i, ""))) }
        assertEquals(Answers.KEEP, many.size)
        assertEquals(Answers.KEEP + 5, many.last().id)
        assertEquals(6, many.first().id)
    }
}
