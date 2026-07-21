package it.drone.mesh.wifi

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicInternetAddressPolicyTest {
    @Test
    fun `given public IPv4 and IPv6 addresses when checked then they are allowed`() {
        assertTrue(PublicInternetAddressPolicy.isPublicAddress(address("8.8.8.8")))
        assertTrue(PublicInternetAddressPolicy.isPublicAddress(address("2606:4700:4700::1111")))
    }

    @Test
    fun `given private or special-use addresses when checked then they are blocked`() {
        listOf(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "224.0.0.1",
            "::",
            "::1",
            "fc00::1",
            "fe80::1",
            "ff02::1",
        ).forEach { assertFalse(PublicInternetAddressPolicy.isPublicAddress(address(it)), it) }
    }

    @Test
    fun `given DNS returns public and private addresses when resolved then entire destination is rejected`() {
        assertThrows(UnknownHostException::class.java) {
            PublicInternetAddressPolicy.resolvePublic("example.test") {
                arrayOf(address("8.8.8.8"), address("127.0.0.1"))
            }
        }
    }

    private fun address(value: String): InetAddress = InetAddress.getByName(value)
}