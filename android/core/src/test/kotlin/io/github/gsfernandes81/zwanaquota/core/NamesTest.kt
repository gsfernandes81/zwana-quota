package io.github.gsfernandes81.zwanaquota.core

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Device names off the network. Packets here are built by hand, byte by byte,
 * the way a router, an mDNS responder and a Windows machine answer.
 */
class NamesTest {
    private val ip = "10.1.0.125"
    private val reverse = "125.0.1.10.in-addr.arpa"

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun name(n: String): ByteArray = ByteArrayOutputStream().apply {
        n.split('.').forEach { write(it.length); write(it.toByteArray()) }
        write(0)
    }.toByteArray()

    /** A PTR response to [id], its answer's target compressed against the question when [compress]. */
    private fun ptrAnswer(id: Int, target: String, rcode: Int = 0, compress: Boolean = true): ByteArray {
        val question = name(reverse) + u16(12) + u16(1)
        val owner = if (compress) byteArrayOf(0xc0.toByte(), 12) else name(reverse)
        // The target's tail, `in-addr.arpa`, is not shared; `local` is spelled out.
        val rdata = name(target)
        return u16(id) + u16(0x8180 or rcode) + u16(1) + u16(1) + u16(0) + u16(0) +
            question + owner + u16(12) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(rdata.size) + rdata
    }

    private fun nodeStatus(id: Int, names: List<Triple<String, Int, Boolean>>): ByteArray {
        val star = Names.nodeStatusQuery(id).copyOfRange(12, 12 + 34)
        val data = ByteArrayOutputStream().apply {
            write(names.size)
            for ((n, suffix, group) in names) {
                write(n.padEnd(15).toByteArray())
                write(suffix)
                write(if (group) 0x84 else 0x04)
                write(0)
            }
            write(ByteArray(46))
        }.toByteArray()
        return u16(id) + u16(0x8400) + u16(0) + u16(1) + u16(0) + u16(0) +
            star + u16(0x21) + u16(1) + byteArrayOf(0, 0, 0, 0) + u16(data.size) + data
    }

    @Test
    fun `the reverse name of an address, and none for anything else`() {
        assertEquals(reverse, Names.reverseName(ip))
        for (bad in listOf("10.1.0", "10.1.0.256", "fe80::1", "a.b.c.d", "", "10.1.0.1.2", "10.1.0.0001")) {
            assertNull(Names.reverseName(bad), bad)
        }
    }

    @Test
    fun `a PTR answer is read, compressed or not`() {
        for (compress in listOf(true, false)) {
            val packet = ptrAnswer(0x1234, "gavins-thinkpad.lan", compress = compress)
            assertEquals("gavins-thinkpad.lan", Names.parsePtr(packet, 0x1234))
        }
    }

    @Test
    fun `an answer to another question, an error, or a query is no answer`() {
        assertNull(Names.parsePtr(ptrAnswer(0x1234, "x.lan"), 0x4321))
        assertNull(Names.parsePtr(ptrAnswer(0x1234, "x.lan", rcode = 3), 0x1234))
        assertNull(Names.parsePtr(Names.ptrQuery(reverse, 0x1234, true), 0x1234))
    }

    @Test
    fun `no packet, however broken, throws`() {
        val good = ptrAnswer(7, "gavins-thinkpad.local")
        val random = Random(7)
        for (cut in good.indices) Names.parsePtr(good.copyOf(cut), 7)
        repeat(2000) {
            val bad = good.copyOf()
            repeat(1 + random.nextInt(4)) { bad[random.nextInt(bad.size)] = random.nextInt(256).toByte() }
            Names.parsePtr(bad, 7)
            Names.parseNodeStatus(bad, 7)
        }
        // A compression pointer to itself.
        val loop = u16(7) + u16(0x8180) + u16(0) + u16(1) + u16(0) + u16(0) + byteArrayOf(0xc0.toByte(), 12)
        assertNull(Names.parsePtr(loop, 7))
    }

    @Test
    fun `the computer name from a node status, not the workgroup or a service`() {
        val packet = nodeStatus(
            9,
            listOf(
                Triple("WORKGROUP", 0x00, true),
                Triple("GAVINS-THINKPAD", 0x20, false),
                Triple("GAVINS-THINKPAD", 0x00, false),
            ),
        )
        assertEquals("GAVINS-THINKPAD", Names.parseNodeStatus(packet, 9))
        assertNull(Names.parseNodeStatus(nodeStatus(9, listOf(Triple("WORKGROUP", 0, true))), 9))
    }

    @Test
    fun `a name is shown as its first label, and not when it only spells the address`() {
        assertEquals("gavins-thinkpad", Names.display("gavins-thinkpad.local.", ip))
        assertEquals("gavins-thinkpad", Names.display("GAVINS-THINKPAD", ip))
        assertEquals("Gavins-iPhone", Names.display("Gavins-iPhone.local", ip))
        for (useless in listOf(null, "", ".", reverse, "host-10-1-0-125.lan", "10.1.0.125", "ip-10_1_0_125")) {
            assertNull(Names.display(useless, ip), useless)
        }
    }

    @Test
    fun `the ways are tried in turn, and the first that answers names the device`() {
        val asked = mutableListOf<Pair<String, Int>>()
        fun resolve(answers: Map<Int, (ByteArray) -> ByteArray?>) = Names.resolve(ip, listOf("10.1.0.1"), { host, port, query, _ ->
            asked += host to port
            answers[port]?.invoke(query)
        }, id = 0x77)

        assertEquals("router-name", resolve(mapOf(53 to { _ -> ptrAnswer(0x77, "router-name.lan") })))
        assertEquals(listOf("10.1.0.1" to 53), asked)

        asked.clear()
        // The router knows nothing (NXDOMAIN); the device answers for itself.
        val name = resolve(
            mapOf(
                53 to { _ -> ptrAnswer(0x77, "x", rcode = 3) },
                5353 to { _ -> ptrAnswer(0x77, "gavins-mac.local") },
            ),
        )
        assertEquals("gavins-mac", name)
        assertEquals(listOf("10.1.0.1" to 53, ip to 5353), asked)

        asked.clear()
        val windows = resolve(mapOf(137 to { _ -> nodeStatus(0x77, listOf(Triple("DESKTOP-7Q", 0, false))) }))
        assertEquals("desktop-7q", windows)
        assertEquals(listOf("10.1.0.1" to 53, ip to 5353, ip to 137), asked)
    }

    @Test
    fun `nothing answering is no name, and a failing socket is nothing answering`() {
        assertNull(Names.resolve(ip, listOf("10.1.0.1"), { _, _, _, _ -> null }))
        assertNull(Names.resolve(ip, emptyList(), { _, _, _, _ -> throw java.io.IOException("EPERM") }))
        assertNull(Names.resolve("not-an-ip", listOf("10.1.0.1"), { _, _, _, _ -> error("must not be asked") }))
    }
}
