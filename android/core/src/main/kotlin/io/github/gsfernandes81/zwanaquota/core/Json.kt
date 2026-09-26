package io.github.gsfernandes81.zwanaquota.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Reading the portal's documents the way zwana_quota.py and quota_widget.py
// read them: field by field, with Python's own idea of what counts as a
// number and what counts as true. The port is held to the Python by the
// golden vectors, and those were written by code that asks
// `isinstance(x, (int, float))` and `bool(x)` -- so these ask the same.

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.list(): JsonArray? = this as? JsonArray

internal operator fun JsonObject?.get(key: String, vararg more: String): JsonElement? {
    var here: JsonElement? = this?.get(key)
    for (next in more) here = (here as? JsonObject)?.get(next)
    return here
}

/**
 * The value if it is a number, as Python's `isinstance(x, (int, float))` sees
 * it: an integral token is a [Long], anything else numeric a [Double]. A
 * quoted `"40"` is a string and not a number, there as here. A JSON boolean
 * *is* a number to Python (`bool` subclasses `int`), so it is one here too.
 */
internal fun JsonElement?.number(): Number? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return when (p.content) {
        "true" -> 1L
        "false" -> 0L
        else -> p.content.toLongOrNull() ?: p.content.toDoubleOrNull()
    }
}

/** Python's `int(x)` for a number: truncation toward zero. */
internal fun Number.truncate(): Long = if (this is Long) this else toDouble().toLong()

/** Python's `bool(x)` for a JSON value. */
internal fun JsonElement?.truthy(): Boolean = when (val p = this) {
    null, is JsonNull -> false
    is JsonPrimitive -> if (p.isString) p.content.isNotEmpty() else p.number()?.toDouble() != 0.0
    is JsonObject -> p.isNotEmpty()
    is JsonArray -> p.isNotEmpty()
}

/** Python's `str(x)` for the fields it is used on: a date, a profile name. */
internal fun JsonElement?.pyStr(): String = when (val p = this) {
    null -> ""
    is JsonNull -> "None"
    is JsonPrimitive -> p.content
    else -> p.toString()
}
