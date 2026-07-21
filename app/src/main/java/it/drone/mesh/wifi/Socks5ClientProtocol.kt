package it.drone.mesh.wifi

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.ProtocolException
import java.nio.charset.StandardCharsets

object Socks5ClientProtocol {
    private const val VERSION = 5
    private const val AUTHENTICATION_NONE = 0
    private const val COMMAND_CONNECT = 1
    private const val ADDRESS_TYPE_IPV4 = 1
    private const val ADDRESS_TYPE_DOMAIN = 3
    private const val ADDRESS_TYPE_IPV6 = 4
    private const val MAX_DOMAIN_LENGTH = 253

    @JvmStatic
    @Throws(IOException::class)
    fun connect(input: InputStream, output: OutputStream, host: String, port: Int) {
        val asciiHost = try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        } catch (exception: IllegalArgumentException) {
            throw ProtocolException("Invalid SOCKS destination: ${exception.message}")
        }
        val hostBytes = asciiHost.toByteArray(StandardCharsets.US_ASCII)
        if (hostBytes.isEmpty() || hostBytes.size > MAX_DOMAIN_LENGTH || port !in 1..65535) {
            throw ProtocolException("Invalid SOCKS destination")
        }

        output.write(byteArrayOf(VERSION.toByte(), 1, AUTHENTICATION_NONE.toByte()))
        output.flush()
        if (readByte(input) != VERSION || readByte(input) != AUTHENTICATION_NONE) {
            throw ProtocolException("SOCKS gateway rejected authentication")
        }

        output.write(
            byteArrayOf(
                VERSION.toByte(),
                COMMAND_CONNECT.toByte(),
                0,
                ADDRESS_TYPE_DOMAIN.toByte(),
                hostBytes.size.toByte(),
            ),
        )
        output.write(hostBytes)
        output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()

        if (readByte(input) != VERSION) {
            throw ProtocolException("Invalid SOCKS gateway response")
        }
        val reply = readByte(input)
        if (readByte(input) != 0) {
            throw ProtocolException("Invalid SOCKS reserved byte")
        }
        val addressLength = when (readByte(input)) {
            ADDRESS_TYPE_IPV4 -> 4
            ADDRESS_TYPE_DOMAIN -> readByte(input)
            ADDRESS_TYPE_IPV6 -> 16
            else -> throw ProtocolException("Invalid SOCKS response address")
        }
        readExactly(input, addressLength + 2)
        if (reply != Socks5Protocol.REPLY_SUCCEEDED) {
            throw ProtocolException("SOCKS gateway rejected destination with code $reply")
        }
    }

    private fun readExactly(input: InputStream, length: Int) {
        var remaining = length
        val buffer = ByteArray(32)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) {
                throw EOFException("SOCKS gateway response ended unexpectedly")
            }
            remaining -= count
        }
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) {
            throw EOFException("SOCKS gateway response ended unexpectedly")
        }
        return value
    }
}