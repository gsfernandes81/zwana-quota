package io.github.gsfernandes81.zwanaquota.core

import java.io.IOException

/**
 * Which way a request to the portal goes.
 *
 * The portal is the vessel's own, on its Wi-Fi. Android normally sends
 * traffic over Wi-Fi anyway; the case that needs help is a captive Wi-Fi it
 * has not validated, where it can leave the default route on mobile data --
 * and the portal is then unreachable, or reached over the metered radio. Only
 * then is a request pinned to the Wi-Fi network.
 *
 * Never while a VPN is the default route. A VPN-based firewall (GlassWire,
 * NetGuard, RethinkDNS) is a VPN, and Android refuses to let an app it covers
 * bind a socket around it -- `Binding socket to network N failed: EPERM`, no
 * matter what the firewall itself allows. Its own route leads to the Wi-Fi
 * anyway: the firewall forwards what it permits.
 */
enum class Route {
    /** Whatever Android routes to: the Wi-Fi, a VPN in front of it, or mobile data if that is all there is. */
    DEFAULT,

    /** Pinned to the Wi-Fi network, around a default route that is not it. */
    PIN_WIFI,
    ;

    companion object {
        fun choose(defaultIsVpn: Boolean, defaultIsWifi: Boolean, wifiPresent: Boolean): Route =
            if (wifiPresent && !defaultIsWifi && !defaultIsVpn) PIN_WIFI else DEFAULT
    }
}

/**
 * [first], and [second] only when [first] was refused before it could send
 * anything: a socket that could not be bound to its network. A refusal like
 * that never reached the portal, so trying again cannot send a login twice;
 * any other failure is the portal's answer, or its silence, and is passed on
 * untouched.
 */
class FallbackTransport(
    private val first: Transport,
    private val second: Transport,
    private val fellBack: (IOException) -> Unit = {},
) : Transport {
    override fun exchange(request: HttpRequest): HttpResponse = try {
        first.exchange(request)
    } catch (e: IOException) {
        if (!refusedLocally(e)) throw e
        fellBack(e)
        second.exchange(request)
    }

    companion object {
        /** A socket that its own phone would not bind: nothing left the device. */
        fun refusedLocally(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(8).any { t ->
                val text = t.message.orEmpty()
                "Binding socket" in text || "EPERM" in text
            }
    }
}
