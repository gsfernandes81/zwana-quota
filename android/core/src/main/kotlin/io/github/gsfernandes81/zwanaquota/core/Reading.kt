package io.github.gsfernandes81.zwanaquota.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A raw reading: what [Pipeline.gather] makes of the portal's three answers,
 * and what the cache holds between runs.
 *
 * The shape and the key names are quota_widget.gather()'s, so a reading from
 * this app and one from Termux can be read side by side -- and so the vectors'
 * `raw` objects parse straight into one.
 */
data class Reading(
    val ts: Double,
    val credits: Double,
    val perCredit: Long,
    val remainder: Long,
    val allocated: Long,
    val grant: Long,
    val drawnToday: Long,
    val online: Boolean,
    val profile: String,
    val poolDay: String,
    val pool: Long,
    val poolFirstTs: Double,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ts", JsonPrimitive(ts))
        put("credits", JsonPrimitive(credits))
        put("per_credit", JsonPrimitive(perCredit))
        put("remainder", JsonPrimitive(remainder))
        put("allocated", JsonPrimitive(allocated))
        put("grant", JsonPrimitive(grant))
        put("drawn_today", JsonPrimitive(drawnToday))
        put("online", JsonPrimitive(online))
        put("profile", JsonPrimitive(profile))
        put("pool_day", JsonPrimitive(poolDay))
        put("pool", JsonPrimitive(pool))
        put("pool_first_ts", JsonPrimitive(poolFirstTs))
    }

    /** What the next [Pipeline.gather] carries forward from this one. */
    fun carry(): Carry = Carry(poolDay, pool, poolFirstTs, grant, drawnToday)

    companion object {
        /**
         * A reading from its JSON, or null if it is not one -- an unreadable
         * cache is no reading, not a crash, exactly as `quota_widget.cached`
         * treats it.
         */
        fun fromJson(element: JsonElement?): Reading? {
            val o = element.obj() ?: return null
            return try {
                val ts = o["ts"].number()?.toDouble() ?: return null
                Reading(
                    ts = ts,
                    credits = o["credits"].number()!!.toDouble(),
                    perCredit = o["per_credit"].number()!!.truncate(),
                    remainder = o["remainder"].number()!!.truncate(),
                    allocated = o["allocated"].number()?.truncate() ?: 0,
                    grant = o["grant"].number()!!.truncate(),
                    drawnToday = o["drawn_today"].number()!!.truncate(),
                    online = o["online"].truthy(),
                    profile = o["profile"]?.let { if (it.truthy()) it.pyStr() else "" } ?: "",
                    poolDay = o["pool_day"].pyStr(),
                    pool = o["pool"].number()!!.truncate(),
                    poolFirstTs = o["pool_first_ts"].number()?.toDouble() ?: ts,
                )
            } catch (_: NullPointerException) {
                null
            }
        }
    }
}

/**
 * The part of an earlier reading the next one carries: today's pool
 * high-water mark and when it was first seen, and the grant and draws to fall
 * back on if the history cannot be read. Every field may be missing, as
 * `previous.get(...)` allows for in the Python.
 */
data class Carry(
    val poolDay: String?,
    val pool: Long,
    val poolFirstTs: Double?,
    val grant: Long,
    val drawnToday: Long,
) {
    companion object {
        fun fromJson(element: JsonElement?): Carry? {
            val o = element.obj() ?: return null
            return Carry(
                poolDay = o["pool_day"]?.takeIf { it.truthy() }?.pyStr(),
                pool = o["pool"].number()?.truncate() ?: 0,
                poolFirstTs = o["pool_first_ts"].number()?.toDouble(),
                grant = o["grant"].number()?.truncate() ?: 0,
                drawnToday = o["drawn_today"].number()?.truncate() ?: 0,
            )
        }
    }
}
