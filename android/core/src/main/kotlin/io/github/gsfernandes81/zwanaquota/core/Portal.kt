package io.github.gsfernandes81.zwanaquota.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.time.Instant

/** Any failure talking to the portal, already stripped of secrets. */
open class PortalError(message: String) : Exception(message)

/** The portal redirected to its login page instead of answering. */
class NotAuthenticated(message: String) : PortalError(message)

/** The login, which is never printed: [toString] leaves the password out. */
data class Credentials(val username: String, val password: String) {
    override fun toString(): String = "Credentials(username=$username, password=***)"
}

class HttpRequest(val url: String, val method: String, val headers: Map<String, String>, val body: ByteArray?)

/** A response. Header names are lower-cased by the transport. */
class HttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray)

/**
 * The seam the tests stub, as the Python suite stubs `zwana_quota.request`.
 * Throws [IOException] when the portal cannot be reached at all.
 */
fun interface Transport {
    fun exchange(request: HttpRequest): HttpResponse
}

/**
 * [Transport] over [HttpURLConnection]. [open] is how the app routes a request
 * onto a particular network (the Wi-Fi the portal lives on) without this
 * module knowing what a network is.
 */
class UrlConnectionTransport(
    private val open: (URL) -> URLConnection = { it.openConnection() },
    private val timeoutMillis: Int = PortalClient.TIMEOUT_SECONDS * 1000,
) : Transport {
    override fun exchange(request: HttpRequest): HttpResponse {
        val connection = open(URL(request.url)) as HttpURLConnection
        try {
            // An unauthenticated call is answered with 302 to the login page,
            // not 401. Followed, that is the HTML shell and a baffling parse
            // error; unfollowed, it is NotAuthenticated.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = request.method
            connection.useCaches = false
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = connection.headerFields
                .filterKeys { it != null }
                .map { (k, v) -> k.lowercase() to v }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, v) -> v.flatten() }
            return HttpResponse(status, headers, body)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The cookies the portal sets, for the one host this talks to.
 *
 * Authentication is really the `.AspNetCore.Identity.Application` cookie; the
 * `access_token` the login returns authorises nothing (README, finding 2).
 * Every cookie is kept rather than only that one, because ASP.NET Core splits
 * a large one into `...C1`, `...C2` chunks, and a jar that kept the name it
 * expected would be carrying half a session.
 */
class CookieJar(initial: Map<String, String> = emptyMap()) {
    private val cookies = LinkedHashMap(initial)

    val entries: Map<String, String> get() = LinkedHashMap(cookies)

    fun has(name: String): Boolean = name in cookies

    fun clear() = cookies.clear()

    /** Take in `Set-Cookie` values. An emptied or expired cookie is dropped. */
    fun absorb(setCookie: List<String>): Boolean {
        var changed = false
        for (header in setCookie) {
            val parts = header.split(';').map { it.trim() }
            val pair = parts.firstOrNull() ?: continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            val maxAge = parts.drop(1)
                .firstOrNull { it.startsWith("max-age=", ignoreCase = true) }
                ?.substringAfter('=')?.toLongOrNull()
            val gone = value.isEmpty() || (maxAge != null && maxAge <= 0)
            changed = if (gone) cookies.remove(name) != null || changed else cookies.put(name, value) != value || changed
        }
        return changed
    }

    fun header(): String? = cookies.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    /** For storage. Cookie names and values cannot hold a newline or a tab. */
    fun serialize(): String = cookies.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    companion object {
        fun parse(text: String?): CookieJar = CookieJar(
            text.orEmpty().lines().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }.toMap(),
        )
    }
}

/**
 * zwana_quota.py's client: log in, keep the session cookie, read the API.
 *
 * [credentials] is asked only when a login is actually needed. [saveSession]
 * is told whenever the cookies change, so a repeat read costs one request
 * instead of two and the login endpoint is not hammered.
 */
class PortalClient(
    private val transport: Transport,
    private val jar: CookieJar = CookieJar(),
    private val credentials: () -> Credentials?,
    private val saveSession: (CookieJar) -> Unit = {},
) {
    companion object {
        const val BASE_URL = "https://ic.zwana.io/api/"

        /** The portal's own front end: the same host, without the API path. */
        val PORTAL_URL: String = BASE_URL.removeSuffix("api/")

        /** The cookie that actually carries the session. */
        const val SESSION_COOKIE = ".AspNetCore.Identity.Application"

        /**
         * `Balance` is in credits, not bytes: one credit is exactly 400 MiB
         * (README, finding 3). Only the fallback -- [Pipeline.gather] reads
         * the provider's own `UnitCost` where the history states it.
         */
        const val BYTES_PER_CREDIT = 419_430_400L

        const val TIMEOUT_SECONDS = 30

        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }

    val hasSession: Boolean get() = jar.has(SESSION_COOKIE)

    /**
     * Call the API and return the decoded JSON body, or null for an empty one.
     * `GET` unless [payload] is given, in which case it is sent as JSON.
     */
    fun request(path: String, payload: JsonElement? = null): JsonElement? {
        val headers = buildMap {
            put("Accept", "application/json")
            if (payload != null) put("Content-Type", "application/json")
            jar.header()?.let { put("Cookie", it) }
        }
        val response = try {
            transport.exchange(
                HttpRequest(BASE_URL + path, if (payload == null) "GET" else "POST", headers, payload?.toString()?.toByteArray()),
            )
        } catch (e: IOException) {
            throw PortalError("$path: cannot reach portal (${e.message ?: e.javaClass.simpleName})")
        }
        if (jar.absorb(response.headers["set-cookie"].orEmpty())) saveSession(jar)

        val text = response.body.toString(Charsets.UTF_8)
        if (response.status in REDIRECTS) {
            throw NotAuthenticated("$path: session not valid (HTTP ${response.status})")
        }
        if (response.status >= 400) {
            // Never the request body: it may hold the password.
            throw PortalError("$path: HTTP ${response.status} ${text.take(300)}".trimEnd())
        }
        if (text.isBlank()) return null
        val parsed = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        // The tree reader takes an unquoted word as a literal, so the login
        // page's HTML would otherwise come back as a "JSON" string.
        if (parsed == null || !isStrictJson(parsed)) {
            throw PortalError("$path: expected JSON, got ${text.take(120)}")
        }
        return parsed
    }

    private fun isStrictJson(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all(::isStrictJson)
        is JsonArray -> element.all(::isStrictJson)
        is JsonNull -> true
        is JsonPrimitive -> element.isString || element.content == "true" || element.content == "false" ||
            element.content.toBigDecimalOrNull() != null
    }

    /** Authenticate, and keep the session cookie that comes back. */
    fun logIn() {
        val login = credentials()
            ?: throw PortalError("no credentials: enter the portal login in the app")
        jar.clear()
        request(
            "account/token",
            buildJsonObject {
                put("grant_type", JsonPrimitive("password"))
                put("username", JsonPrimitive(login.username))
                put("password", JsonPrimitive(login.password))
            },
        )
        if (!hasSession) throw PortalError("login returned no session cookie; credentials rejected?")
        saveSession(jar)
    }

    /** GET [path], logging in once -- and only once -- if the session has gone stale. */
    fun fetch(path: String, allowLogin: Boolean = true): JsonElement? = try {
        request(path)
    } catch (e: NotAuthenticated) {
        if (!allowLogin) throw e
        logIn()
        request(path)
    }

    /**
     * One reading: quota_widget.gather()'s three calls. A failed history is
     * tolerated -- [Pipeline.gather] falls back on [previous] -- and anything
     * else is a [PortalError].
     */
    fun read(previous: Carry?, now: Instant): Reading {
        if (!hasSession) logIn()
        val balance = fetch("Balance/GetForCurrentUser")
        Pipeline.checkBalance(balance)
        val active = fetch("UserProvider/GetActive")
        Pipeline.checkActive(active)
        val history = try {
            fetch("Allocation/GetHistoryForCurrentUser")
        } catch (_: PortalError) {
            null
        }
        return Pipeline.gather(balance, active, history, previous, now)
    }
}
