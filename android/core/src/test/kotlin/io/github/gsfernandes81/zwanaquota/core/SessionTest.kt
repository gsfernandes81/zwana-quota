package io.github.gsfernandes81.zwanaquota.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The data switch. The rule the widget is held to: off is off for everyone
 * only when this phone is the one that switched data on; a phone that joined
 * someone else's session only ever takes itself off.
 */
class SessionTest {
    private val me = "10.1.0.225"
    private val other = "10.1.0.125"

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun status(state: String, ip: String?) =
        json("""{"ip": ${ip?.let { "\"$it\"" } ?: "null"}, "provider": 0, "status": "$state"}""")

    private fun client(ip: String?) = json("""{"ip": ${ip?.let { "\"$it\"" } ?: "null"}}""")

    private fun joined(vararg ips: String) =
        json(ips.joinToString(",", "{", "}") { """"$it": {"Joined": "2026-09-26T10:00:00", "Mac": "aa:bb"}""" })

    @Test
    fun `each role and what the switch does from it`() {
        val cases = listOf(
            Session.from(status("off", null), client(me), null) to (Role.OFF to SessionAction.TURN_ON),
            Session.from(status("quota", me), client(me), joined(other)) to (Role.PRIMARY to SessionAction.TURN_OFF_EVERYWHERE),
            Session.from(status("quota", other), client(me), joined(me)) to (Role.JOINED to SessionAction.LEAVE),
            Session.from(status("quota", other), client(me), null) to (Role.OUTSIDE to SessionAction.JOIN),
            Session.from(status("quota", other), client(null), null) to (Role.UNKNOWN to null),
        )
        for ((session, expected) in cases) {
            assertEquals(expected.first, session.role, session.toString())
            assertEquals(expected.second, session.action, session.toString())
        }
    }

    @Test
    fun `a provider the portal did not state is never guessed, so data is not switched`() {
        val noProvider = json("""{"ip": "$me", "status": "quota"}""")
        val on = Session.from(noProvider, client(me), null)
        assertEquals(Role.PRIMARY, on.role)
        assertNull(on.action)
        val off = Session.from(json("""{"ip": null, "status": "off"}"""), client(me), null)
        assertNull(off.action)
        assertEquals(off, Session.fromJson(off.toJson()))
    }

    @Test
    fun `a blank status is not data on`() {
        assertFalse(Session.from(json("""{"ip": "$other", "provider": 0, "status": ""}"""), client(me), null).on)
    }

    @Test
    fun `only a change that takes a device off asks first`() {
        assertTrue(SessionAction.TURN_OFF_EVERYWHERE.confirm)
        assertTrue(SessionAction.LEAVE.confirm)
        assertFalse(SessionAction.TURN_ON.confirm)
        assertFalse(SessionAction.JOIN.confirm)
    }

    @Test
    fun `a joined phone is never offered off-for-everyone, whatever else is joined`() {
        for (others in listOf(emptyList(), listOf(other), listOf(other, "10.1.0.9"))) {
            val s = Session.from(status("quota", "10.1.0.9"), client(me), joined(*(others + me).toTypedArray()))
            assertEquals(SessionAction.LEAVE, s.action)
        }
    }

    @Test
    fun `devices are the primary and the joined, this phone first, named and never by MAC`() {
        val s = Session.from(status("quota", other), client(me), joined(me, "10.1.0.9"))
        assertEquals(listOf(other, me, "10.1.0.9"), s.devices)
        val shown = s.devices(mapOf(other to "gavins-thinkpad"))
        assertEquals(listOf(me, other, "10.1.0.9"), shown.map { it.ip })
        assertTrue(shown.first().me)
        assertEquals(Session.THIS_PHONE, shown.first().label)
        assertEquals("gavins-thinkpad", shown[1].label)
        assertEquals("10.1.0.9", shown[2].label)
        assertTrue(shown.none { ":" in it.label })
    }

    @Test
    fun `data off covers no devices, however the portal still lists them`() {
        val s = Session.from(status("off", me), client(me), joined(other))
        assertTrue(s.devices.isEmpty())
        assertTrue(s.devices(emptyMap()).isEmpty())
    }

    @Test
    fun `the summary counts the other devices`() {
        val alone = Session.from(status("quota", me), client(me), null)
        val two = Session.from(status("quota", me), client(me), joined(other, "10.1.0.9"))
        val outside = Session.from(status("quota", other), client(me), null)
        assertFalse(alone.summary().any { it.isDigit() })
        assertTrue("2" in two.summary())
        assertTrue("1" in outside.summary())
    }

    @Test
    fun `a session survives its own storage`() {
        for (s in listOf(
            Session.from(status("quota", other), client(me), joined(me)),
            Session.from(status("off", null), client(null), null),
        )) {
            assertEquals(s, Session.fromJson(s.toJson()))
        }
        assertNull(Session.fromJson(null))
        assertNull(Session.fromJson(json("[1]")))
    }

    /** A portal that keeps the session state and records every call. */
    private class Portal(var on: Boolean, var primary: String?, val joined: MutableSet<String>, val myIp: String) {
        val posts = mutableListOf<Pair<String, String>>()
        val transport = Transport { r ->
            val path = r.url.removePrefix(PortalClient.BASE_URL)
            val body = r.body?.decodeToString()
            fun ok(text: String) = HttpResponse(200, emptyMap(), text.toByteArray())
            when (path) {
                "UserProvider/GetStatus" -> ok(
                    """{"ip": ${primary?.let { "\"$it\"" } ?: "null"}, "provider": 3, "status": "${if (on) "quota" else "off"}"}""",
                )
                "UserProvider/GetClientIP" -> ok("""{"ip": "$myIp"}""")
                "Device/GetJoinedDevices" -> if (joined.isEmpty()) {
                    HttpResponse(404, emptyMap(), ByteArray(0))
                } else {
                    ok(joined.joinToString(",", "{", "}") { """"$it": {"Mac": "x"}""" })
                }
                else -> {
                    posts += path to body.orEmpty()
                    ok("")
                }
            }
        }
    }

    private fun PortalClient(portal: Portal) = PortalClient(
        portal.transport,
        CookieJar(mapOf(PortalClient.SESSION_COOKIE to "abc")),
        credentials = { null },
    )

    @Test
    fun `nobody joined is a 404, and is no one rather than a failure`() {
        val s = PortalClient(Portal(true, me, mutableSetOf(), me)).session()
        assertEquals(emptyList(), s.joined)
        assertEquals(Role.PRIMARY, s.role)
    }

    @Test
    fun `off and on are the account switch, with the provider the portal reported`() {
        for ((on, action) in listOf(false to SessionAction.TURN_ON, true to SessionAction.TURN_OFF_EVERYWHERE)) {
            val portal = Portal(on, if (on) me else null, mutableSetOf(), me)
            PortalClient(portal).apply(action)
            val (path, body) = portal.posts.single()
            assertEquals("Account/UpdateForCurrentUser", path)
            val sent = Json.parseToJsonElement(body).jsonObject
            assertEquals((!on).toString(), sent["Started"]!!.jsonPrimitive.content)
            assertEquals("3", sent["ProviderNo"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `leaving and joining name this phone and nothing else`() {
        for ((joined, action, path) in listOf(
            Triple(mutableSetOf(me), SessionAction.LEAVE, "Device/RemoveDevice"),
            Triple(mutableSetOf(), SessionAction.JOIN, "Device/JoinDevice"),
        )) {
            val portal = Portal(true, other, joined, me)
            PortalClient(portal).apply(action)
            val (sentPath, body) = portal.posts.single()
            assertEquals(path, sentPath)
            assertEquals(me, Json.parseToJsonElement(body).jsonObject["ip"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a switch drawn before the session changed does nothing`() {
        // The widget said "Data on, this phone switched it on"; since then
        // someone else took over and this phone only joined. Tapping off must
        // not turn off *their* session.
        val portal = Portal(true, other, mutableSetOf(me), me)
        assertFailsWith<SessionChanged> { PortalClient(portal).apply(SessionAction.TURN_OFF_EVERYWHERE) }
        assertTrue(portal.posts.isEmpty())
        for (action in SessionAction.entries - SessionAction.LEAVE) {
            assertFailsWith<SessionChanged> { PortalClient(portal).apply(action) }
        }
        assertTrue(portal.posts.isEmpty())
    }

    @Test
    fun `a switch sent over an expired session logs in and is sent once`() {
        var loggedIn = false
        val posts = mutableListOf<String>()
        val transport = Transport { r ->
            val path = r.url.removePrefix(PortalClient.BASE_URL)
            when {
                path == "account/token" -> {
                    loggedIn = true
                    HttpResponse(200, mapOf("set-cookie" to listOf("${PortalClient.SESSION_COOKIE}=new")), "{}".toByteArray())
                }
                !loggedIn -> HttpResponse(302, emptyMap(), ByteArray(0))
                else -> {
                    posts += path
                    HttpResponse(200, emptyMap(), ByteArray(0))
                }
            }
        }
        val client = PortalClient(transport, CookieJar(mapOf(PortalClient.SESSION_COOKIE to "old")), credentials = { Credentials("u", "p") })
        client.send("Device/JoinDevice", Json.parseToJsonElement("""{"ip": "$me"}"""))
        assertEquals(listOf("Device/JoinDevice"), posts)
    }
}
