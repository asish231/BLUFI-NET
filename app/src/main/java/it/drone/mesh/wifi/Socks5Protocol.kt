package it.drone.mesh.wifi

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.InetAddress
import java.net.ProtocolException
import java.nio.charset.StandardCharsets

data class Socks5ConnectRequest(val host: String, val port: Int)

object Socks5Protocol {
    const val REPLY_SUCCEEDED = 0
    const val REPLY_GENERAL_FAILURE = 1
    const val REPLY_CONNECTION_NOT_ALLOWED = 2
    const val REPLY_NETWORK_UNREACHABLE = 3
    const val REPLY_HOST_UNREACHABLE = 4
    const val REPLY_CONNECTION_REFUSED = 5
    const val REPLY_COMMAND_NOT_SUPPORTED = 7
    const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 8

    private const val VERSION = 5
    private const val AUTHENTICATION_NONE = 0
    private const val AUTHENTICATION_UNACCEPTABLE = 0xff
    private const val COMMAND_CONNECT = 1
    private const val ADDRESS_TYPE_IPV4 = 1
    private const val ADDRESS_TYPE_DOMAIN = 3
    private const val ADDRESS_TYPE_IPV6 = 4
    private const val MAX_DOMAIN_LENGTH = 253

    @JvmStatic
    @Throws(EOFException::class, ProtocolException::class)
    fun readConnectRequest(input: InputStream, output: OutputStream): Socks5ConnectRequest {
        negotiateAuthentication(input, output)
        if (readByte(input) != VERSION) {
            throw ProtocolException("Unsupported SOCKS request version")
        }
        if (readByte(input) != COMMAND_CONNECT) {
            writeReply(output, REPLY_COMMAND_NOT_SUPPORTED)
            throw ProtocolException("Only SOCKS CONNECT is supported")
        }
        if (readByte(input) != 0) {
            writeReply(output, REPLY_GENERAL_FAILURE)
            throw ProtocolException("Invalid SOCKS reserved byte")
        }

        val host = when (readByte(input)) {
            ADDRESS_TYPE_IPV4 -> readAddress(input, 4)
            ADDRESS_TYPE_DOMAIN -> readDomain(input)
            ADDRESS_TYPE_IPV6 -> readAddress(input, 16)
            else -> {
                writeReply(output, REPLY_ADDRESS_TYPE_NOT_SUPPORTED)
                throw ProtocolException("Unsupported SOCKS address type")
            }
        }
        val port = (readByte(input) shl 8) or readByte(input)
        if (port == 0) {
            writeReply(output, REPLY_CONNECTION_NOT_ALLOWED)
            throw ProtocolException("SOCKS destination port must not be zero")
        }
        return Socks5ConnectRequest(host, port)
    }

    @JvmStatic
    fun writeReply(output: OutputStream, replyCode: Int) {
        require(replyCode in 0..255) { "Invalid SOCKS reply code" }
        output.write(
            byteArrayOf(
                VERSION.toByte(),
                replyCode.toByte(),
                0,
                ADDRESS_TYPE_IPV4.toByte(),
                0,
                0,
                0,
                0,
                0,
                0,
            ),
        )
        output.flush()
    }

    private fun negotiateAuthentication(input: InputStream, output: OutputStream) {
        if (readByte(input) != VERSION) {
            throw ProtocolException("Unsupported SOCKS greeting version")
        }
        val methodCount = readByte(input)
        if (methodCount == 0) {
            throw ProtocolException("SOCKS greeting contains no authentication methods")
        }
        var supportsNoAuthentication = false
        repeat(methodCount) {
            supportsNoAuthentication = supportsNoAuthentication or (readByte(input) == AUTHENTICATION_NONE)
        }
        val selected = if (supportsNoAuthentication) AUTHENTICATION_NONE else AUTHENTICATION_UNACCEPTABLE
        output.write(byteArrayOf(VERSION.toByte(), selected.toByte()))
        output.flush()
        if (!supportsNoAuthentication) {
            throw ProtocolException("SOCKS client does not support no-authentication mode")
        }
    }

    private fun readDomain(input: InputStream): String {
        val length = readByte(input)
        if (length == 0 || length > MAX_DOMAIN_LENGTH) {
            throw ProtocolException("Invalid SOCKS domain length")
        }
        val rawDomain = String(readExactly(input, length), StandardCharsets.US_ASCII)
        val domain = try {
            IDN.toASCII(rawDomain, IDN.USE_STD3_ASCII_RULES)
        } catch (exception: IllegalArgumentException) {
            throw ProtocolException("Invalid SOCKS domain: ${exception.message}")
        }
        if (domain.isEmpty() || domain.length > MAX_DOMAIN_LENGTH) {
            throw ProtocolException("Invalid SOCKS domain")
        }
        return domain
    }

    private fun readAddress(input: InputStream, length: Int): String =
        InetAddress.getByAddress(readExactly(input, length)).hostAddress
            ?: throw ProtocolException("Invalid SOCKS IP address")

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) {
                throw EOFException("SOCKS request ended unexpectedly")
            }
            offset += count
        }
        return result
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) {
            throw EOFException("SOCKS request ended unexpectedly")
        }
        return value
    }
}