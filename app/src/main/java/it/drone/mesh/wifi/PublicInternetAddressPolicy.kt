package it.drone.mesh.wifi

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

fun interface MeshHostResolver {
    @Throws(UnknownHostException::class)
    fun resolve(host: String): Array<InetAddress>
}

object PublicInternetAddressPolicy {
    private val defaultResolver = MeshHostResolver(InetAddress::getAllByName)

    @JvmStatic
    @JvmOverloads
    @Throws(UnknownHostException::class)
    fun resolvePublic(
        host: String,
        resolver: MeshHostResolver = defaultResolver,
    ): Array<InetAddress> {
        if (host.isBlank() || host.length > MAX_HOST_LENGTH) {
            throw UnknownHostException("Invalid destination host")
        }
        val addresses = resolver.resolve(host)
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw UnknownHostException("Destination does not resolve exclusively to public addresses")
        }
        return addresses.copyOf()
    }

    @JvmStatic
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        return when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return when {
            first == 0 || first == 10 || first == 127 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 && third in 0..2 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        if (first and 0xfe == 0xfc) {
            return false
        }
        if (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) {
            return false
        }
        return true
    }

    private const val MAX_HOST_LENGTH = 253
}