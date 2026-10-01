package io.github.gsfernandes81.zwanaquota.core

/**
 * The data session as the watch is sent it: words and lists the phone has
 * already decided, so the watch holds no rules about who may switch what.
 * Added to [WatchPayload] only when the session is known.
 *
 * - `dat` `on` or `off`; absent when the session has not been read, and then
 *   the watch's session pages say so.
 * - `dsub` how this phone stands in it, in words.
 * - `dn` the devices' names, this phone first; `dx` how many there are in
 *   all, since at most [MAX_DEVICES] are sent. `dr` is, per name, how that
 *   device is on the session, in words: the watch gives each device a page.
 * - `ctl` whether the watch may switch data and take devices off. Only then
 *   are `act` (what START does to the session, a [SessionAction] name),
 *   `actl` (that, in words), `cq` (the question to ask first, or empty) and
 *   `dip` (per name, the IP the watch may ask to take off, or empty) sent.
 */
object WatchSession {
    const val MAX_DEVICES = 8

    fun fields(session: Session, names: Map<String, String>, canControl: Boolean): Map<String, Any> {
        val devices = session.devices(names)
        val sent = devices.take(MAX_DEVICES)
        val out = linkedMapOf<String, Any>(
            "dat" to if (session.on) "on" else "off",
            "dsub" to when (session.role) {
                Role.PRIMARY -> "switched on here"
                Role.JOINED -> "this phone joined"
                Role.OUTSIDE -> "not on this phone"
                Role.OFF, Role.UNKNOWN -> ""
            },
            "dn" to ArrayList(sent.map { it.label }),
            "dr" to ArrayList(sent.map { if (it.ip == session.primaryIp) "switched data on" else "joined" }),
            "dx" to devices.size,
            "ctl" to canControl,
        )
        if (canControl) {
            val action = session.action
            out["act"] = action?.name ?: ""
            out["actl"] = when (action) {
                SessionAction.TURN_ON -> "Turn on"
                SessionAction.TURN_OFF_EVERYWHERE -> "Turn off for all"
                SessionAction.LEAVE -> "Disconnect phone"
                SessionAction.JOIN -> "Join"
                null -> ""
            }
            out["cq"] = when (action) {
                SessionAction.TURN_OFF_EVERYWHERE ->
                    if (devices.size > 1) "Data off for all ${devices.size} devices?" else "Turn data off?"
                SessionAction.LEAVE -> "Take this phone off data?"
                else -> ""
            }
            out["dip"] = ArrayList(sent.map { if (session.removable(it.ip)) it.ip else "" })
        }
        return out
    }
}

/** What the watch can ask of the phone. Anything else it sends is ignored. */
sealed interface WatchCommand {
    /** Read the portal and send the result. */
    data object Refresh : WatchCommand

    /** Throw the data switch, as the widget's pill does. */
    data class Act(val action: SessionAction) : WatchCommand

    /** Take one other device off the session. */
    data class Remove(val ip: String) : WatchCommand

    companion object {
        /**
         * The watch's message as the Connect IQ SDK hands it over: a list
         * whose first element is the dictionary the watch sent. Null for
         * anything that is not one of the three shapes, including an action
         * or an address that does not parse -- nothing is guessed.
         */
        fun parse(message: List<Any?>?): WatchCommand? {
            val map = message?.firstOrNull() as? Map<*, *> ?: return null
            (map["do"] as? String)?.let { name ->
                return SessionAction.entries.firstOrNull { it.name == name }?.let(::Act)
            }
            (map["rm"] as? String)?.let { ip ->
                return if (Names.reverseName(ip) != null) Remove(ip) else null
            }
            return if (map["ask"] == "refresh") Refresh else null
        }
    }
}
