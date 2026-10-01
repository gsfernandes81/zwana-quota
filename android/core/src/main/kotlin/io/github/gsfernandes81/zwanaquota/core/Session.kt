package io.github.gsfernandes81.zwanaquota.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The portal's data session: whether data is on, which device switched it
 * on, and which devices have joined it.
 *
 * Read off the portal's own web app (tools/portal_probe.py, portal_source.py):
 *
 * - `UserProvider/GetStatus` is `{"ip", "provider", "status"}`; a status of
 *   `off` means data is off, anything else (`quota`) that it is on, and `ip`
 *   is the device that switched it on -- the *primary*.
 * - `Device/GetJoinedDevices` maps each joined device's IP to `{Joined, Mac}`,
 *   and answers 404 when nothing has joined.
 * - `UserProvider/GetClientIP` is `{"ip"}`: this phone, as the portal sees it.
 *
 * Its switch is `Account/UpdateForCurrentUser {Started, ProviderNo}`, which
 * turns data on or off for the whole account; a joined device leaves with
 * `Device/RemoveDevice {ip}` and joins with `Device/JoinDevice {ip}`.
 */
data class Session(
    val on: Boolean,
    val primaryIp: String?,
    /** The provider the portal reported; null if it did not say, and then data is not switched. */
    val provider: Int?,
    val myIp: String?,
    val joined: List<String>,
    /** Each joined device's MAC, by IP, as the portal lists it: what an IP handed out again cannot fake. */
    val macs: Map<String, String> = emptyMap(),
) {
    val role: Role
        get() = when {
            !on -> Role.OFF
            myIp == null -> Role.UNKNOWN
            myIp == primaryIp -> Role.PRIMARY
            myIp in joined -> Role.JOINED
            else -> Role.OUTSIDE
        }

    /** What tapping the switch does from here, or null if nothing safely can. */
    val action: SessionAction?
        get() = when (role) {
            // The account switch names a provider; one that is not known is
            // not guessed at.
            Role.OFF -> SessionAction.TURN_ON.takeIf { provider != null }
            Role.PRIMARY -> SessionAction.TURN_OFF_EVERYWHERE.takeIf { provider != null }
            Role.JOINED -> SessionAction.LEAVE
            Role.OUTSIDE -> SessionAction.JOIN
            Role.UNKNOWN -> null
        }

    /** Every device the session covers: the primary first, then the joined ones. */
    val devices: List<String>
        get() = if (!on) emptyList() else (listOfNotNull(primaryIp) + joined).distinct()

    /**
     * Each device as the widget names it: this phone as "This phone", any
     * other by the name the network gave it ([names], by IP), else by its IP
     * -- never its MAC. This phone first, then the session's own order.
     */
    fun devices(names: Map<String, String>): List<Device> = devices
        .map { ip -> Device(ip, if (ip == myIp) THIS_PHONE else names[ip] ?: ip, ip == myIp) }
        .sortedByDescending { it.me }

    /** The devices as one line, for a widget too short for the list. */
    fun summary(): String {
        if (!on) return "no devices: data is off"
        val all = devices
        val others = all.count { it != myIp }
        fun devices(n: Int) = if (n == 1) "1 device" else "$n devices"
        return when {
            myIp != null && myIp in all -> if (others == 0) "$THIS_PHONE only" else "$THIS_PHONE + ${devices(others)}"
            myIp != null -> "${devices(all.size)}, not this phone"
            else -> devices(all.size)
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("on", JsonPrimitive(on))
        put("primary", primaryIp?.let(::JsonPrimitive) ?: JsonNull)
        put("provider", provider?.let(::JsonPrimitive) ?: JsonNull)
        put("me", myIp?.let(::JsonPrimitive) ?: JsonNull)
        put("joined", JsonArray(joined.map(::JsonPrimitive)))
        put("macs", JsonObject(macs.mapValues { JsonPrimitive(it.value) }))
    }

    companion object {
        const val THIS_PHONE = "This phone"

        fun fromJson(element: JsonElement?): Session? {
            val o = element.obj() ?: return null
            return Session(
                on = o["on"].truthy(),
                primaryIp = o["primary"]?.takeUnless { it is JsonNull }?.pyStr(),
                provider = o["provider"]?.takeUnless { it is JsonNull }.number()?.truncate()?.toInt(),
                myIp = o["me"]?.takeUnless { it is JsonNull }?.pyStr(),
                joined = o["joined"].list()?.map { it.pyStr() }.orEmpty(),
                macs = o["macs"].obj()?.mapValues { it.value.pyStr() }.orEmpty(),
            )
        }

        /** The three answers as a session. Pure, so the rules can be tested. */
        fun from(status: JsonElement?, clientIp: JsonElement?, joinedDevices: JsonElement?): Session {
            val s = status.obj()
            val state = s?.get("status")?.pyStr()
            return Session(
                // Only a status that says something is on: blank is not.
                on = !state.isNullOrBlank() && state != "off",
                primaryIp = s?.get("ip")?.takeUnless { it is JsonNull }?.pyStr()?.ifBlank { null },
                provider = s?.get("provider")?.takeUnless { it is JsonNull }.number()?.truncate()?.toInt(),
                myIp = clientIp.obj()?.get("ip")?.takeUnless { it is JsonNull }?.pyStr()?.ifBlank { null },
                joined = joinedDevices.obj()?.keys?.sorted().orEmpty(),
                macs = joinedDevices.obj().orEmpty()
                    .mapNotNull { (ip, v) -> v.obj()?.get("Mac")?.takeUnless { it is JsonNull }?.pyStr()?.let { ip to it } }
                    .toMap(),
            )
        }
    }
}

data class Device(val ip: String, val label: String, val me: Boolean)

enum class Role { OFF, PRIMARY, JOINED, OUTSIDE, UNKNOWN }

/** What the widget's switch can do, and whether it asks first. */
enum class SessionAction(val confirm: Boolean) {
    /** Data on, with this phone as the device that switched it on. */
    TURN_ON(false),

    /** This phone switched data on, so off is off for every device -- as on the portal. */
    TURN_OFF_EVERYWHERE(true),

    /** This phone joined someone else's session: only this phone leaves. */
    LEAVE(true),

    /** Data is on for another device and this phone is not in it: join. */
    JOIN(false),
}

/** The session as the portal reports it now. Nothing here changes it. */
fun PortalClient.session(): Session {
    if (!hasSession) logIn()
    val status = fetch("UserProvider/GetStatus")
    val ip = fetch("UserProvider/GetClientIP")
    val joined = try {
        fetch("Device/GetJoinedDevices")
    } catch (e: PortalError) {
        // The portal's own app treats 404 as "nobody has joined".
        if (e.status == 404) null else throw e
    }
    return Session.from(status, ip, joined)
}

/**
 * Do [action], but only if it is still what the session calls for: a widget
 * is a picture that may be minutes old, and a tap on "Data on" drawn before
 * someone else took over must not turn off *their* session. Returns the
 * session after the change. [allowed] is asked once more just before the
 * change is sent: a switch past its deadline ([TooLate]) is not made.
 */
fun PortalClient.apply(action: SessionAction, allowed: () -> Boolean = { true }): Session {
    val now = session()
    if (now.action != action) {
        throw SessionChanged("the session changed since the widget was drawn (now ${now.role.name.lowercase()}); nothing was done")
    }
    if (!allowed()) throw TooLate("past its deadline; nothing was done")
    when (action) {
        SessionAction.TURN_ON -> send("Account/UpdateForCurrentUser", started(true, now.provider!!))
        SessionAction.TURN_OFF_EVERYWHERE -> send("Account/UpdateForCurrentUser", started(false, now.provider!!))
        SessionAction.LEAVE -> send("Device/RemoveDevice", ip(now.myIp!!))
        SessionAction.JOIN -> send("Device/JoinDevice", ip(now.myIp!!))
    }
    return session()
}

class SessionChanged(message: String) : PortalError(message)

/** A switch or removal whose deadline passed before it was sent: nothing was done. */
class TooLate(message: String) : PortalError(message)

private fun started(on: Boolean, provider: Int) = buildJsonObject {
    put("Started", JsonPrimitive(on))
    put("ProviderNo", JsonPrimitive(provider))
}

private fun ip(address: String) = buildJsonObject { put("ip", JsonPrimitive(address)) }

/**
 * Take another device off the session: [ipToRemove] only, and only the
 * device with [expectedMac] when that is given, and only if it is
 * still a joined device other than this phone and other than the one that
 * switched data on (which leaves only by data going off, [SessionAction]).
 * Checked against the session as it is now, as [apply] is, so a list drawn
 * minutes ago cannot remove a device that has since become something else.
 */
fun PortalClient.remove(ipToRemove: String, expectedMac: String? = null, allowed: () -> Boolean = { true }): Session {
    val now = session()
    if (!now.removable(ipToRemove)) {
        throw SessionChanged("$ipToRemove is not a device this phone can take off now; nothing was done")
    }
    // The address was offered for one device; if the portal now lists another
    // MAC at it, the address has been handed out again and this is not it.
    if (expectedMac != null && now.macs[ipToRemove]?.equals(expectedMac, ignoreCase = true) == false) {
        throw SessionChanged("$ipToRemove is now a different device; nothing was done")
    }
    if (!allowed()) throw TooLate("past its deadline; nothing was done")
    send("Device/RemoveDevice", ip(ipToRemove))
    return session()
}

/** Whether [address] is a joined device other than this phone and the primary: what [remove] may take off. */
fun Session.removable(address: String): Boolean =
    on && Names.reverseName(address) != null && address in joined && address != myIp && address != primaryIp
