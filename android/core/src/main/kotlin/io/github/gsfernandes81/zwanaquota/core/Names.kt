package io.github.gsfernandes81.zwanaquota.core

/**
 * One UDP question and its answer. Null when nothing came back in time, the
 * way a device on a network that isolates its clients answers everything.
 * The seam the tests stub; the app sends it over the Wi-Fi.
 */
fun interface Datagram {
    fun exchange(host: String, port: Int, query: ByteArray, timeoutMillis: Int): ByteArray?
}

/**
 * A device's name, asked of the network by its IP. The portal knows devices
 * only by IP and MAC, and a MAC tells a person nothing, so the widget asks
 * three ways a local network answers, in turn, and shows the IP when none do:
 *
 * 1. **reverse DNS** of the Wi-Fi's own DNS servers -- a router that handed
 *    out the address usually knows the hostname the device asked it for;
 * 2. **mDNS**, asked of the device itself on 5353 -- phones, Macs and Linux
 *    laptops answer for their own `.local` name;
 * 3. **NetBIOS**, asked of the device on 137 -- Windows answers with its
 *    computer name.
 *
 * Only the question bytes and the parsing are here, so both can be tested
 * against fixed packets; the sockets are the app's.
 */
object Names {
    const val DNS_PORT = 53
    const val MDNS_PORT = 5353
    const val NETBIOS_PORT = 137
    const val TIMEOUT_MILLIS = 700

    private const val TYPE_PTR = 12
    private const val TYPE_NBSTAT = 0x21

    /**
     * The name of [ip] by the first way that answers, as a person would read
     * it (`gavins-thinkpad`), or null if none does.
     */
    fun resolve(ip: String, dnsServers: List<String>, net: Datagram, id: Int = 0x5a17): String? {
        val reverse = reverseName(ip) ?: return null
        val tries = dnsServers.map { Triple(it, DNS_PORT, ptrQuery(reverse, id, recursive = true)) } +
            Triple(ip, MDNS_PORT, ptrQuery(reverse, id, recursive = false)) +
            Triple(ip, NETBIOS_PORT, nodeStatusQuery(id))
        for ((host, port, query) in tries) {
            val answer = try {
                net.exchange(host, port, query, TIMEOUT_MILLIS)
            } catch (_: Exception) {
                null
            } ?: continue
            val name = if (port == NETBIOS_PORT) parseNodeStatus(answer, id) else parsePtr(answer, id)
            display(name, ip)?.let { return it }
        }
        return null
    }

    /** `10.1.0.125` as `125.0.1.10.in-addr.arpa`, or null if it is not an IPv4 address. */
    fun reverseName(ip: String): String? {
        val octets = ip.split('.')
        if (octets.size != 4 || octets.any { o -> o.toIntOrNull()?.takeIf { it in 0..255 } == null || o.length > 3 }) {
            return null
        }
        return octets.reversed().joinToString(".", postfix = ".in-addr.arpa")
    }

    /**
     * A name fit to show for [ip]: the first label, lower-cased if it came
     * all in capitals (NetBIOS does). Null for no name, for the reverse name
     * itself, and for a name that only spells the address back
     * (`host-10-1-0-125.lan`), which says less than the IP does.
     */
    fun display(name: String?, ip: String): String? {
        val full = name?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        if (full.endsWith(".arpa", ignoreCase = true)) return null
        val label = full.substringBefore('.').trim()
        if (label.isEmpty() || label.any { it.isISOControl() }) return null
        val octets = ip.split('.')
        if (octets.size == 4 && Regex(octets.joinToString("[-_.]")).containsMatchIn(full)) return null
        return if (label == label.uppercase()) label.lowercase() else label
    }

    /** A PTR question. Recursion is asked of a DNS server and not of a device's mDNS. */
    fun ptrQuery(name: String, id: Int, recursive: Boolean): ByteArray =
        header(id, if (recursive) 0x0100 else 0) + encodeName(name) + u16(TYPE_PTR) + u16(1)

    /** NetBIOS's node status question: "every name you hold", asked of `*`. */
    fun nodeStatusQuery(id: Int): ByteArray {
        val star = ByteArray(16).also { it[0] = '*'.code.toByte() }
        val encoded = ByteArray(32)
        for (i in star.indices) {
            val b = star[i].toInt() and 0xff
            encoded[2 * i] = ('A'.code + (b shr 4)).toByte()
            encoded[2 * i + 1] = ('A'.code + (b and 0x0f)).toByte()
        }
        return header(id, 0) + byteArrayOf(32) + encoded + byteArrayOf(0) + u16(TYPE_NBSTAT) + u16(1)
    }

    /** The first PTR target in a response to [id], or null. Never throws on a bad packet. */
    fun parsePtr(packet: ByteArray, id: Int): String? = answers(packet, id)
        .firstOrNull { it.type == TYPE_PTR }
        ?.let { readName(packet, it.offset)?.first }

    /**
     * The computer's own name from a node status response: the first unique
     * (not group) name with the workstation suffix 0x00.
     */
    fun parseNodeStatus(packet: ByteArray, id: Int): String? {
        val rr = answers(packet, id).firstOrNull { it.type == TYPE_NBSTAT } ?: return null
        if (rr.length < 1) return null
        val count = packet[rr.offset].toInt() and 0xff
        for (i in 0 until count) {
            val at = rr.offset + 1 + i * 18
            if (at + 18 > rr.offset + rr.length) return null
            val suffix = packet[at + 15].toInt() and 0xff
            val group = (packet[at + 16].toInt() and 0x80) != 0
            if (suffix == 0 && !group) {
                return String(packet, at, 15, Charsets.ISO_8859_1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private class Record(val type: Int, val offset: Int, val length: Int)

    /** The answer records of a well-formed response to [id]; empty for anything else. */
    private fun answers(packet: ByteArray, id: Int): List<Record> {
        if (packet.size < 12 || u16At(packet, 0) != id) return emptyList()
        val flags = u16At(packet, 2)
        if (flags and 0x8000 == 0 || flags and 0x000f != 0) return emptyList()
        val questions = u16At(packet, 4)
        val count = u16At(packet, 6)
        var at = 12
        repeat(questions) {
            at = (readName(packet, at) ?: return emptyList()).second + 4
        }
        val out = mutableListOf<Record>()
        repeat(count) {
            at = (readName(packet, at) ?: return out).second
            if (at + 10 > packet.size) return out
            val type = u16At(packet, at)
            val length = u16At(packet, at + 8)
            val data = at + 10
            if (data + length > packet.size) return out
            out += Record(type, data, length)
            at = data + length
        }
        return out
    }

    /**
     * The name at [start] and the offset just past it where it sits (not
     * where its compression pointers lead). Null for a malformed or looping one.
     */
    private fun readName(packet: ByteArray, start: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var at = start
        var end = -1
        var jumps = 0
        while (true) {
            if (at >= packet.size) return null
            val len = packet[at].toInt() and 0xff
            when {
                len == 0 -> {
                    if (end < 0) end = at + 1
                    return labels.joinToString(".") to end
                }
                len and 0xc0 == 0xc0 -> {
                    if (at + 1 >= packet.size || ++jumps > 16) return null
                    if (end < 0) end = at + 2
                    at = ((len and 0x3f) shl 8) or (packet[at + 1].toInt() and 0xff)
                }
                len and 0xc0 != 0 -> return null
                else -> {
                    if (at + 1 + len > packet.size) return null
                    labels += String(packet, at + 1, len, Charsets.UTF_8)
                    at += 1 + len
                }
            }
        }
    }

    private fun header(id: Int, flags: Int) = u16(id) + u16(flags) + u16(1) + u16(0) + u16(0) + u16(0)

    private fun encodeName(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8 and 0xff).toByte(), (v and 0xff).toByte())

    private fun u16At(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)
}
