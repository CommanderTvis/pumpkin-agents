package io.github.commandertvis.pumpkins.e2e

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal Source RCON client (Valve protocol). Supports the three packet types we need:
 * AUTH (3), EXECCOMMAND (2), RESPONSE_VALUE (0). Single-threaded, blocking.
 */
class RconClient(host: String, port: Int, private val password: String) : Closeable {
    private val socket = Socket(host, port).apply { tcpNoDelay = true }
    private val out = DataOutputStream(socket.getOutputStream())
    private val input = DataInputStream(socket.getInputStream())
    private var requestId = 0

    fun authenticate() {
        val id = nextId()
        send(id, TYPE_AUTH, password)
        // Some servers send a dummy RESPONSE_VALUE first; skip if so.
        var pkt = recv()
        if (pkt.type == TYPE_RESPONSE_VALUE) pkt = recv()
        check(pkt.type == TYPE_AUTH_RESPONSE) { "Unexpected auth response type ${pkt.type}" }
        check(pkt.id == id) { "RCON auth failed (id mismatch, server probably rejected the password)" }
    }

    fun execute(command: String): String {
        val id = nextId()
        send(id, TYPE_EXECCOMMAND, command)
        val pkt = recv()
        check(pkt.id == id) { "RCON id mismatch (expected $id, got ${pkt.id})" }
        return pkt.body
    }

    private fun send(id: Int, type: Int, body: String) {
        val payload = body.toByteArray(Charsets.UTF_8)
        val total = 4 + 4 + payload.size + 2
        val buf = ByteBuffer.allocate(4 + total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(total)
        buf.putInt(id)
        buf.putInt(type)
        buf.put(payload)
        buf.put(0.toByte())
        buf.put(0.toByte())
        out.write(buf.array())
        out.flush()
    }

    private data class Packet(val id: Int, val type: Int, val body: String)

    private fun recv(): Packet {
        val length = readInt()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val id = bb.int
        val type = bb.int
        val body = ByteArray(length - 10)
        bb.get(body)
        // last 2 bytes are the dual null terminator
        return Packet(id, type, String(body, Charsets.UTF_8))
    }

    private fun readInt(): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun nextId(): Int = ++requestId

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val TYPE_RESPONSE_VALUE = 0
        const val TYPE_EXECCOMMAND = 2
        const val TYPE_AUTH_RESPONSE = 2
        const val TYPE_AUTH = 3
    }
}
