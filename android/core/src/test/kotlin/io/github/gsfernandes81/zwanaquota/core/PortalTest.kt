package io.github.gsfernandes81.zwanaquota.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client against a stub [Transport], the seam the Python suite stubs as
 * `zwana_quota.request`. Nothing here reaches the network.
 */
class PortalTest {
    private val login = Credentials("crew42", "hunter2-secret")
    private val session = "${PortalClient.SESSION_COOKIE}=abc; path=/; secure; httponly"

    /** Answers by path, recording every request. */
    private class Stub(val answer: (HttpRequest) -> HttpResponse) : Transport {
        val seen = mutableListOf<HttpRequest>()
        override fun exchange(request: HttpRequest): HttpResponse {
            seen += request
            return answer(request)
        }
    }

    private fun ok(body: String, vararg cookies: String) =
        HttpResponse(200, mapOf("set-cookie" to cookies.toList()), body.toByteArray())

    private fun status(code: Int, body: String = "") = HttpResponse(code, emptyMap(), body.toByteArray())

    private fun path(r: HttpRequest) = r.url.removePrefix(PortalClient.BASE_URL)

    @Test
    fun `the login is a JSON body and the session is the cookie it sets`() {
        val stub = Stub { ok("""{"access_token":"x","token_type":"password"}""", session) }
        var saved: CookieJar? = null
        val client = PortalClient(stub, credentials = { login }, saveSession = { saved = it })
        client.logIn()

        val sent = stub.seen.single()
        assertEquals("POST", sent.method)
        assertEquals("application/json", sent.headers["Content-Type"])
        val body = Json.parseToJsonElement(sent.body!!.decodeToString()).jsonObject
        assertEquals("password", body["grant_type"]!!.jsonPrimitive.content)
        assertEquals(login.username, body["username"]!!.jsonPrimitive.content)
        assertTrue(client.hasSession)
        assertTrue(saved!!.has(PortalClient.SESSION_COOKIE))
    }

    @Test
    fun `a login that sets no session cookie is refused, and the token is not trusted`() {
        val client = PortalClient(Stub { ok("""{"access_token":"x"}""") }, credentials = { login })
        assertFailsWith<PortalError> { client.logIn() }
        assertFalse(client.hasSession)
    }

    @Test
    fun `no credentials is a portal error, not a crash`() {
        val client = PortalClient(Stub { error("must not be called") }, credentials = { null })
        assertFailsWith<PortalError> { client.read(null, Instant.EPOCH) }.also {
            assertFalse(it is NotAuthenticated)
        }
    }

    @Test
    fun `every redirect is NotAuthenticated, and anything else over 400 a PortalError`() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            val client = PortalClient(Stub { status(code) }, credentials = { null })
            assertFailsWith<NotAuthenticated> { client.request("x") }
        }
        for (code in listOf(400, 401, 403, 404, 500, 503)) {
            val client = PortalClient(Stub { status(code, "nope") }, credentials = { null })
            val e = assertFailsWith<PortalError> { client.request("x") }
            assertFalse(e is NotAuthenticated, "HTTP $code")
        }
    }

    @Test
    fun `an unreachable portal is a PortalError`() {
        val client = PortalClient({ throw IOException("no route") }, credentials = { null })
        assertFailsWith<PortalError> { client.request("x") }
    }

    @Test
    fun `a body that is not JSON is a PortalError, and an empty one is nothing`() {
        for (body in listOf("<html>login</html>", "{\"a\": <b>}", "[1, nope]", "{")) {
            assertFailsWith<PortalError>(body) {
                PortalClient(Stub { ok(body) }, credentials = { null }).request("x")
            }
        }
        for (body in listOf("3", "true", "null", "\"s\"", "[1.5e3, -2]", "{\"a\": {\"b\": [false]}}")) {
            PortalClient(Stub { ok(body) }, credentials = { null }).request("x")
        }
        assertNull(PortalClient(Stub { ok("  ") }, credentials = { null }).request("x"))
    }

    @Test
    fun `no error ever carries the password`() {
        // The portal echoing the request back is the case that matters: the
        // error keeps the response body, so it must be the response and never
        // the request that went into it.
        val outcomes = listOf(
            status(500, "internal error"),
            status(400, "bad request"),
            ok("not json"),
            ok("{}"),
            status(302),
        )
        for (outcome in outcomes) {
            val client = PortalClient(Stub { outcome }, credentials = { login })
            val e = assertFailsWith<PortalError> { client.logIn() }
            assertFalse(login.password in e.message.orEmpty(), e.message)
        }
        assertFalse(login.password in login.toString())
    }

    @Test
    fun `a stale session logs in once and retries once, and only once`() {
        var logins = 0
        val stub = Stub { r ->
            when (path(r)) {
                "account/token" -> ok("{}", session).also { logins++ }
                else -> status(302)
            }
        }
        val client = PortalClient(stub, CookieJar(mapOf(PortalClient.SESSION_COOKIE to "old")), { login })
        assertFailsWith<NotAuthenticated> { client.fetch("Balance/GetForCurrentUser") }
        assertEquals(1, logins)
        assertEquals(3, stub.seen.size)
    }

    @Test
    fun `a retried request after a fresh login succeeds`() {
        var loggedIn = false
        val stub = Stub { r ->
            when {
                path(r) == "account/token" -> ok("{}", session).also { loggedIn = true }
                loggedIn -> ok("""{"Balance": 3}""")
                else -> status(302)
            }
        }
        val client = PortalClient(stub, CookieJar(mapOf(PortalClient.SESSION_COOKIE to "old")), { login })
        assertEquals("3", client.fetch("Balance/GetForCurrentUser")!!.jsonObject["Balance"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the cookie goes back on the next request`() {
        val stub = Stub { r -> if (path(r) == "account/token") ok("{}", session, "other=1") else ok("{}") }
        val client = PortalClient(stub, credentials = { login })
        client.logIn()
        client.request("x")
        val cookie = stub.seen.last().headers["Cookie"]!!
        assertTrue("${PortalClient.SESSION_COOKIE}=abc" in cookie)
        assertTrue("other=1" in cookie)
    }

    @Test
    fun `the jar keeps chunked cookies, drops emptied ones, and survives storage`() {
        val jar = CookieJar()
        jar.absorb(listOf("a=1; path=/", "${PortalClient.SESSION_COOKIE}C1=part; path=/", "b=2"))
        jar.absorb(listOf("b=; expires=Thu, 01 Jan 1970 00:00:00 GMT", "a=3; Max-Age=0"))
        assertEquals(mapOf("${PortalClient.SESSION_COOKIE}C1" to "part"), jar.entries)
        assertEquals(jar.entries, CookieJar.parse(jar.serialize()).entries)
        assertEquals(emptyMap(), CookieJar.parse(null).entries)
    }

    @Test
    fun `a reading is the three calls, and a lost history is survivable`() {
        val stub = Stub { r ->
            when (path(r)) {
                "Balance/GetForCurrentUser" -> ok("""{"Balance": 3.0, "Online": true}""")
                "UserProvider/GetActive" -> ok("""{"Remainder": 800063488, "Allocated": 1}""")
                "Allocation/GetHistoryForCurrentUser" -> status(500)
                else -> error("unexpected ${r.url}")
            }
        }
        val client = PortalClient(stub, CookieJar(mapOf(PortalClient.SESSION_COOKIE to "s")), { login })
        val reading = client.read(null, Instant.parse("2026-09-02T20:30:00Z"))
        assertEquals(800063488L, reading.remainder)
        assertEquals(0L, reading.grant)
        assertEquals(3, stub.seen.size)
    }

    @Test
    fun `a balance without a number stops the reading before anything else is asked`() {
        val stub = Stub { ok("""{"Balance": "three"}""") }
        val client = PortalClient(stub, CookieJar(mapOf(PortalClient.SESSION_COOKIE to "s")), { login })
        assertFailsWith<PortalError> { client.read(null, Instant.EPOCH) }
        assertEquals(1, stub.seen.size)
    }

    @Test
    fun `the portal address is derived from the API's, not spelled twice`() {
        assertEquals("https://ic.zwana.io/", PortalClient.PORTAL_URL)
        assertTrue(PortalClient.BASE_URL.startsWith(PortalClient.PORTAL_URL))
    }
}
