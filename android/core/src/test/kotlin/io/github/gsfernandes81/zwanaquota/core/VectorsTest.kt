package io.github.gsfernandes81.zwanaquota.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The port against the numbers the live Python wrote (vectors/quota.json).
 *
 * If this fails after a `make vectors`, the Python changed and this copy has
 * not caught up -- carry the change across, do not regenerate around it.
 */
class VectorsTest {
    private val vectors: JsonObject by lazy {
        val path = System.getProperty("vectors") ?: fail("the build passes -Dvectors=<repo>/vectors/quota.json")
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    @Test
    fun `the vectors are the schema this port reads`() {
        assertEquals(1, vectors["schema"]!!.jsonPrimitive.content.toInt())
        assertTrue(vectors["gather"]!!.jsonArray.size >= 10)
        assertTrue(vectors["derive"]!!.jsonArray.size >= 40)
    }

    @Test
    fun `gather reproduces every reading the Python gathered`() {
        val failures = vectors["gather"]!!.jsonArray.mapNotNull { element ->
            val case = element.jsonObject
            val now = instant(case["now"]!!)
            val history = case["history"].takeUnless { it is JsonNull }
            val actual: JsonElement = try {
                Pipeline.gather(case["balance"], case["active"], history, Carry.fromJson(case["previous"]), now).toJson()
            } catch (_: PortalError) {
                buildJsonObject { put("error", JsonPrimitive(true)) }
            }
            mismatch(case["expect"]!!, actual, "")?.let { "${case["name"]}: $it" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `derive reproduces every document the Python derived`() {
        val failures = vectors["derive"]!!.jsonArray.mapNotNull { element ->
            val case = element.jsonObject
            val raw = Reading.fromJson(case["raw"]) ?: return@mapNotNull "${case["name"]}: raw did not parse"
            val doc = Pipeline.derive(
                raw,
                case["age"]!!.jsonPrimitive.content.toDouble(),
                case["live"]!!.jsonPrimitive.content.toBooleanStrict(),
                instant(case["now"]!!),
            )
            mismatch(case["expect"]!!, asVector(doc), "")?.let { "${case["name"]}: $it" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** make_vectors.DERIVED, in the Python's names. */
    private fun asVector(doc: Document): JsonObject {
        val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
        fun t(i: Instant) = JsonPrimitive(iso.format(i.atOffset(ZoneOffset.UTC)))
        return buildJsonObject {
            put("free", buildJsonObject {
                put("grant_bytes", JsonPrimitive(doc.grantBytes))
                put("left_bytes", JsonPrimitive(doc.freeLeftBytes))
                put("used_bytes", JsonPrimitive(doc.freeUsedBytes))
                put("expires", t(doc.reset))
            })
            put("paid", buildJsonObject {
                put("left_bytes", JsonPrimitive(doc.paidLeftBytes))
                put("drawn_today_bytes", JsonPrimitive(doc.drawnTodayBytes))
                put("carry_in_bytes", JsonPrimitive(doc.carryInBytes))
            })
            put("today", buildJsonObject {
                put("pool_bytes", JsonPrimitive(doc.poolBytes))
                put("remainder_bytes", JsonPrimitive(doc.remainderBytes))
                put("used_bytes", JsonPrimitive(doc.todayUsedBytes))
            })
            put("reserve", buildJsonObject {
                put("credits", JsonPrimitive(doc.credits))
                put("bytes_per_credit", JsonPrimitive(doc.bytesPerCredit))
                put("bytes", JsonPrimitive(doc.reserveBytes))
            })
            put("reset", buildJsonObject {
                put("utc", t(doc.reset))
                put("seconds_until", JsonPrimitive(doc.secondsUntilReset))
            })
            put("accuracy", buildJsonObject {
                put("free_left_is_upper_bound", JsonPrimitive(doc.freeLeftIsUpperBound))
                put("first_reading_after_reset_seconds", JsonPrimitive(doc.firstReadingAfterResetSeconds))
                put("carry_in_observed", JsonPrimitive(doc.carryInObserved))
            })
            put("reading", buildJsonObject {
                put("online", JsonPrimitive(doc.online))
                put("live", JsonPrimitive(doc.live))
            })
        }
    }

    private fun instant(seconds: JsonElement): Instant = Pipeline.instantOf(seconds.jsonPrimitive.content.toDouble())

    /**
     * Where [actual] departs from [expected], or null. Numbers compare by
     * value, so Python's `3.0` and a Kotlin `3` are the same figure; every key
     * the Python wrote must be present and equal, and no extra ones appear.
     */
    private fun mismatch(expected: JsonElement, actual: JsonElement, path: String): String? = when {
        expected is JsonObject && actual is JsonObject -> {
            val keys = expected.keys + actual.keys
            keys.sorted().firstNotNullOfOrNull { k ->
                val e = expected[k] ?: return@firstNotNullOfOrNull "$path.$k is extra (${actual[k]})"
                val a = actual[k] ?: return@firstNotNullOfOrNull "$path.$k is missing (want $e)"
                mismatch(e, a, "$path.$k")
            }
        }
        expected is JsonArray && actual is JsonArray ->
            if (expected.size != actual.size) "$path has ${actual.size} items, want ${expected.size}"
            else expected.indices.firstNotNullOfOrNull { mismatch(expected[it], actual[it], "$path[$it]") }
        expected is JsonPrimitive && actual is JsonPrimitive -> {
            val same = if (!expected.isString && !actual.isString &&
                expected.content.toBigDecimalOrNull() != null && actual.content.toBigDecimalOrNull() != null
            ) {
                BigDecimal(expected.content).compareTo(BigDecimal(actual.content)) == 0
            } else {
                expected.isString == actual.isString && expected.content == actual.content
            }
            if (same) null else "$path = $actual, want $expected"
        }
        else -> "$path = $actual, want $expected"
    }
}
