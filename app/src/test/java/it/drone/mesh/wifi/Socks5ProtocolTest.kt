package it.drone.mesh.wifi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.ProtocolException
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Socks5ProtocolTest {
    @Test
    fun `given a domain connect request when parsed then destination and success negotiation are returned`() {
        val host = "example.com"
        val input = ByteArrayInputStream(
            byteArrayOf(5, 1, 0, 5, 1, 0, 3, host.length.toByte()) +
                host.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(1, -69),
        )
        val output = ByteArrayOutputStream()

        val request = Socks5Protocol.readConnectRequest(input, output)

        assertEquals(host, request.host)
        assertEquals(443, request.port)
        assertArrayEquals(byteArrayOf(5, 0), output.toByteArray())
    }

    @Test
    fun `given a client without no-auth support when parsed then negotiation is rejected`() {
        val input = ByteArrayInputStream(byteArrayOf(5, 1, 2))
        val output = ByteArrayOutputStream()

        assertThrows(ProtocolException::class.java) {
            Socks5Protocol.readConnectRequest(input, output)
        }
        assertArrayEquals(byteArrayOf(5, -1), output.toByteArray())
    }

    @Test
    fun `given a truncated connect request when parsed then end of stream is reported`() {
        val input = ByteArrayInputStream(byteArrayOf(5, 1, 0, 5, 1))

        assertThrows(EOFException::class.java) {
            Socks5Protocol.readConnectRequest(input, ByteArrayOutputStream())
        }
    }

    @Test
    fun `given an unsupported SOCKS command when parsed then command is rejected`() {
        val input = ByteArrayInputStream(byteArrayOf(5, 1, 0, 5, 2, 0, 1, 1, 1, 1, 1, 0, 80))

        assertThrows(ProtocolException::class.java) {
            Socks5Protocol.readConnectRequest(input, ByteArrayOutputStream())
        }
    }

    @Test
    fun `given a reply code when response is written then a valid SOCKS5 reply is produced`() {
        val output = ByteArrayOutputStream()

        Socks5Protocol.writeReply(output, Socks5Protocol.REPLY_SUCCEEDED)

        assertArrayEquals(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0), output.toByteArray())
    }
}