package it.drone.mesh.wifi

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

class WifiDirectSocketFactory(
    gatewayAddress: String,
    private val gatewayPort: Int,
    private val handshakeTimeoutMillis: Int = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
) : SocketFactory() {
    private val gateway = InetAddress.getByName(gatewayAddress).also {
        require(!it.isAnyLocalAddress && !it.isLoopbackAddress) {
            "Gateway must be a Wi-Fi Direct interface address"
        }
    }

    init {
        require(gatewayPort in 1..65535) { "Gateway port is invalid" }
        require(handshakeTimeoutMillis > 0) { "Handshake timeout is invalid" }
    }

    override fun createSocket(): Socket = MeshTunnelSocket()

    override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
        connect(InetSocketAddress.createUnresolved(host, port), handshakeTimeoutMillis)
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress.createUnresolved(host, port), handshakeTimeoutMillis)
        }

    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = createSocket(address.hostAddress, port, localAddress, localPort)

    private inner class MeshTunnelSocket : Socket() {
        @Synchronized
        @Throws(IOException::class)
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            if (isConnected) {
                throw IOException("Socket is already connected")
            }
            val destination = endpoint as? InetSocketAddress
                ?: throw IOException("Unsupported destination address")
            val destinationHost = destination.hostString
            val destinationPort = destination.port
            val connectTimeout = if (timeout > 0) timeout else handshakeTimeoutMillis
            try {
                super.connect(InetSocketAddress(gateway, gatewayPort), connectTimeout)
                val previousTimeout = soTimeout
                soTimeout = handshakeTimeoutMillis
                Socks5ClientProtocol.connect(inputStream, outputStream, destinationHost, destinationPort)
                soTimeout = previousTimeout
            } catch (exception: IOException) {
                runCatching { close() }
                throw exception
            }
        }
    }

    companion object {
        private const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000

        @JvmStatic
        fun unresolvedAddress(hostname: String): InetAddress =
            InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 1))
    }
}