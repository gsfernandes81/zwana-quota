package io.github.gsfernandes81.zwanaquota.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the watch is told about the session, and what it may ask back. The
 * rule under test: the watch can only ever ask for what the phone offered,
 * and the phone checks it again against the portal before doing it.
 */
class WatchTest {
    private val me = "10.1.0.225"
    private val laptop = "10.1.0.125"
    private val tablet = "10.1.0.31"

    @Test
    fun `the three commands, and nothing else`() {
        assertEquals(WatchCommand.Refresh, WatchCommand.parse(listOf(mapOf("ask" to "refresh"))))
        assertEquals(
            WatchCommand.Act(SessionAction.TURN_OFF_EVERYWHERE),
            WatchCommand.parse(listOf(mapOf("do" to "TURN_OFF_EVERYWHERE"))),
        )
        assertEquals(WatchCommand.Remove(laptop), WatchCommand.parse(listOf(mapOf("rm" to laptop))))
        for (junk in listOf(
            null, emptyList(), listOf("refresh"), listOf(mapOf("do" to "FORMAT_DISK")), listOf(mapOf("rm" to "10.1.0")),
            listOf(mapOf("rm" to "10.1.0.1; drop")), listOf(mapOf("do" to 3)), listOf(mapOf("ask" to "yes")), listOf(emptyMap<String, String>()),
        )) {
            assertNull(WatchCommand.parse(junk), "$junk")
        }
    }

    @Test
    fun `an ask's id is read beside its command, and only a whole number is one`() {
        val asked = listOf(mapOf("rm" to laptop, "id" to 1790000001))
        assertEquals(WatchCommand.Remove(laptop), WatchCommand.parse(asked))
        assertEquals(1790000001, WatchCommand.idOf(asked))
        for (none in listOf(null, emptyList(), listOf(mapOf("ask" to "refresh")), listOf(mapOf("id" to "7")), listOf(mapOf("id" to 7.0)))) {
            assertNull(WatchCommand.idOf(none), "$none")
        }
    }

    @Test
    fun `a removal past its deadline is not sent`() {
        val posts = mutableListOf<Pair<String, String>>()
        assertFailsWith<TooLate> { client(true, me, setOf(laptop), posts).remove(laptop) { false } }
        assertTrue(posts.isEmpty())
    }

    private fun session(primary: String, joined: List<String>, on: Boolean = true) = Session(on, primary, 0, me, joined)

    @Test
    fun `the watch is offered control only when the phone allows it`() {
        val s = session(me, listOf(laptop))
        val off = WatchSession.fields(s, emptyMap(), canControl = false)
        assertFalse(off["ctl"] as Boolean)
        for (key in listOf("act", "actl", "cq", "dip")) assertFalse(key in off, key)
        val on = WatchSession.fields(s, emptyMap(), canControl = true)
        assertTrue(on["ctl"] as Boolean)
        assertEquals("TURN_OFF_EVERYWHERE", on["act"])
        assertTrue((on["cq"] as String).isNotEmpty(), "taking devices off asks first")
        assertEquals("", WatchSession.fields(session(me, emptyList(), on = false), emptyMap(), true)["cq"], "switching on does not")
    }

    @Test
    fun `only other joined devices may be taken off, never this phone or the one that switched data on`() {
        val s = session(laptop, listOf(me, tablet))
        val fields = WatchSession.fields(s, mapOf(laptop to "gavins-thinkpad"), canControl = true)
        @Suppress("UNCHECKED_CAST")
        val names = fields["dn"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val ips = fields["dip"] as List<String>
        assertEquals(Session.THIS_PHONE, names.first())
        assertEquals(names.size, ips.size)
        assertEquals(mapOf(Session.THIS_PHONE to "", "gavins-thinkpad" to "", tablet to tablet), names.zip(ips).toMap())
    }

    @Test
    fun `each device sent has its words, and only the one that switched data on says so`() {
        for (s in listOf(session(me, listOf(laptop, tablet)), session(laptop, listOf(me, tablet)), session(laptop, emptyList()))) {
            val fields = WatchSession.fields(s, emptyMap(), canControl = false)
            val names = fields["dn"] as List<*>
            val roles = fields["dr"] as List<*>
            assertEquals(names.size, roles.size)
            assertTrue(roles.all { it is String && it.isNotEmpty() }, "$roles")
            // The primary's words are its own: no other device shares them.
            val devices = s.devices(emptyMap())
            val primary = roles[devices.indexOfFirst { it.ip == s.primaryIp }]
            devices.forEachIndexed { i, d -> assertEquals(d.ip == s.primaryIp, roles[i] == primary, "$roles") }
        }
        val off = WatchSession.fields(session(me, listOf(laptop), on = false), emptyMap(), canControl = false)
        assertTrue((off["dr"] as List<*>).isEmpty())
    }

    @Test
    fun `only the types the Connect IQ SDK carries, and at most eight devices`() {
        val many = (1..20).map { "10.1.0.${it + 1}" }
        val fields = WatchSession.fields(session(me, many), emptyMap(), canControl = true)
        for ((k, v) in fields) {
            assertTrue(v is String || v is Int || v is Boolean || (v is List<*> && v.all { it is String }), "$k is ${v::class}")
        }
        assertEquals(WatchSession.MAX_DEVICES, (fields["dn"] as List<*>).size)
        assertEquals(21, fields["dx"])
    }

    /** A portal holding one session, recording what is posted. */
    private fun client(on: Boolean, primary: String?, joined: Set<String>, posts: MutableList<Pair<String, String>>) = PortalClient(
        { r ->
            val path = r.url.removePrefix(PortalClient.BASE_URL)
            fun ok(t: String) = HttpResponse(200, emptyMap(), t.toByteArray())
            when (path) {
                "UserProvider/GetStatus" -> ok("""{"ip": ${primary?.let { "\"$it\"" } ?: "null"}, "provider": 0, "status": "${if (on) "quota" else "off"}"}""")
                "UserProvider/GetClientIP" -> ok("""{"ip": "$me"}""")
                "Device/GetJoinedDevices" -> if (joined.isEmpty()) HttpResponse(404, emptyMap(), ByteArray(0)) else ok(joined.joinToString(",", "{", "}") { "\"$it\": {\"Mac\": \"mac-$it\"}" })
                else -> {
                    posts += path to r.body!!.decodeToString()
                    ok("")
                }
            }
        },
        CookieJar(mapOf(PortalClient.SESSION_COOKIE to "x")),
        credentials = { null },
    )

    @Test
    fun `removing a device names that device, and only when it is still removable`() {
        val posts = mutableListOf<Pair<String, String>>()
        client(true, me, setOf(laptop), posts).remove(laptop)
        val (path, body) = posts.single()
        assertEquals("Device/RemoveDevice", path)
        assertEquals(laptop, Json.parseToJsonElement(body).jsonObject["ip"]!!.jsonPrimitive.content)

        posts.clear()
        for ((c, ip) in listOf(
            client(true, me, setOf(laptop), posts) to me,
            client(true, laptop, setOf(me), posts) to laptop,
            client(true, me, setOf(tablet), posts) to laptop,
            client(false, null, setOf(laptop), posts) to laptop,
        )) {
            assertFailsWith<SessionChanged> { c.remove(ip) }
        }
        assertTrue(posts.isEmpty())
    }

    @Test
    fun `an address handed to a different device since it was offered is not removed`() {
        val posts = mutableListOf<Pair<String, String>>()
        assertFailsWith<SessionChanged> { client(true, me, setOf(laptop), posts).remove(laptop, expectedMac = "mac-someone-else") }
        assertTrue(posts.isEmpty())
        client(true, me, setOf(laptop), posts).remove(laptop, expectedMac = "MAC-$laptop")
        assertEquals(1, posts.size)
    }

    @Test
    fun `the MACs survive the session's storage`() {
        val s = Session(true, me, 0, me, listOf(laptop), mapOf(laptop to "aa:bb"))
        assertEquals(s, Session.fromJson(s.toJson()))
    }
}
