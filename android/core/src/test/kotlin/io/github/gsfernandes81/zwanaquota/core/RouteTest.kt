package io.github.gsfernandes81.zwanaquota.core

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which way the portal is reached, and what happens when the phone refuses that way. */
class RouteTest {
    @Test
    fun `a request is pinned to the Wi-Fi only around a default route that is neither it nor a VPN`() {
        for (vpn in listOf(false, true)) for (wifiDefault in listOf(false, true)) for (present in listOf(false, true)) {
            val expected = if (present && !wifiDefault && !vpn) Route.PIN_WIFI else Route.DEFAULT
            assertEquals(expected, Route.choose(vpn, wifiDefault, present), "vpn=$vpn wifiDefault=$wifiDefault present=$present")
        }
    }

    @Test
    fun `a VPN firewall in front of the Wi-Fi is never pinned around`() {
        // GlassWire's case: the VPN is the default route, the Wi-Fi is up behind it.
        assertEquals(Route.DEFAULT, Route.choose(defaultIsVpn = true, defaultIsWifi = false, wifiPresent = true))
    }

    private val ok = HttpResponse(200, emptyMap(), "{}".toByteArray())
    private val request = HttpRequest("https://ic.zwana.io/api/x", "POST", emptyMap(), "{}".toByteArray())

    private class Counting(val answer: () -> HttpResponse) : Transport {
        var calls = 0
        override fun exchange(request: HttpRequest): HttpResponse {
            calls++
            return answer()
        }
    }

    @Test
    fun `a socket the phone would not bind falls back to the default route, once`() {
        val refused = SocketException("Binding socket to network 785 failed: EPERM (Operation not permitted)")
        val first = Counting { throw refused }
        val second = Counting { ok }
        var told: IOException? = null
        assertSame(ok, FallbackTransport(first, second) { told = it }.exchange(request))
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertSame(refused, told)
    }

    @Test
    fun `a refusal found further down the causes still counts`() {
        val wrapped = IOException("connect failed", SocketException("EPERM (Operation not permitted)"))
        assertTrue(FallbackTransport.refusedLocally(wrapped))
    }

    @Test
    fun `any other failure is the portal's, and is not retried`() {
        // A request that may have reached the portal must not be sent twice:
        // a login posted twice is two logins.
        for (failure in listOf(SocketTimeoutException("Read timed out"), IOException("Connection reset"), SocketException("Network is unreachable"))) {
            val first = Counting { throw failure }
            val second = Counting { ok }
            val thrown = assertFailsWith<IOException> { FallbackTransport(first, second).exchange(request) }
            assertSame(failure, thrown)
            assertEquals(0, second.calls, failure.toString())
        }
    }

    @Test
    fun `the portal client sees the fallback as one successful request`() {
        val first = Counting { throw SocketException("Binding socket to network 785 failed: EPERM (Operation not permitted)") }
        val second = Counting { HttpResponse(200, emptyMap(), """{"Balance": 3}""".toByteArray()) }
        val client = PortalClient(FallbackTransport(first, second), credentials = { null })
        client.request("Balance/GetForCurrentUser")
        assertEquals(1, second.calls)
    }

    @Test
    fun `once refused, the pinned way is not tried again by the same client`() {
        var pinnedTries = 0
        val pinned = Transport {
            pinnedTries++
            throw java.io.IOException("Binding socket to network 785 failed: EPERM (Operation not permitted)")
        }
        val direct = Transport { HttpResponse(200, emptyMap(), ByteArray(0)) }
        val transport = FallbackTransport(pinned, direct)
        repeat(3) { transport.exchange(HttpRequest("https://x/", "GET", emptyMap(), null)) }
        kotlin.test.assertEquals(1, pinnedTries)
    }

    @Test
    fun `a body is read only so far`() {
        val big = java.io.ByteArrayInputStream(ByteArray(100_000))
        kotlin.test.assertEquals(1000, readAtMost(big, 1000).size)
        kotlin.test.assertEquals(5, readAtMost(java.io.ByteArrayInputStream(ByteArray(5)), 1000).size)
    }
}
