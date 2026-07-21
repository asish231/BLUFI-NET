package it.drone.mesh.security

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import com.google.crypto.tink.signature.SignatureKeyTemplates
import java.security.GeneralSecurityException
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SecureMeshProtocolTest {
    @Test
    fun `given a client and gateway when request completes then relays cannot read either payload`() {
        val client = protocol(InMemoryPeerTrustStore())
        val gateway = protocol(InMemoryPeerTrustStore())
        val url = "https://example.com/private?q=secret"
        val responseBody = "private response body"

        val hello = client.beginRequest(CLIENT_ID, url)
        val acknowledgement = gateway.acceptHello(hello.payload, CLIENT_ID, GATEWAY_ID)
        val request = client.acceptHelloAcknowledgement(
            acknowledgement.payload,
            GATEWAY_ID,
            CLIENT_ID,
        )
        val decryptedRequest = gateway.decryptRequest(request.payload, CLIENT_ID, GATEWAY_ID)
        val response = gateway.encryptResponse(
            decryptedRequest.requestId,
            CLIENT_ID,
            GATEWAY_ID,
            responseBody,
        )
        val decryptedResponse = client.decryptResponse(response.payload, GATEWAY_ID, CLIENT_ID)

        assertEquals(url, decryptedRequest.plaintext)
        assertEquals(responseBody, decryptedResponse.plaintext)
        listOf(hello.payload, acknowledgement.payload, request.payload, response.payload).forEach {
            assertFalse(it.contains(url))
            assertFalse(it.contains(responseBody))
        }
    }

    @Test
    fun `given a modified gateway acknowledgement when client verifies it then verification fails`() {
        val client = protocol(InMemoryPeerTrustStore())
        val gateway = protocol(InMemoryPeerTrustStore())
        val hello = client.beginRequest(CLIENT_ID, "https://example.com")
        val acknowledgement = gateway.acceptHello(hello.payload, CLIENT_ID, GATEWAY_ID)

        assertThrows(GeneralSecurityException::class.java) {
            client.acceptHelloAcknowledgement(
                tamper(acknowledgement.payload),
                GATEWAY_ID,
                CLIENT_ID,
            )
        }
    }

    @Test
    fun `given a pinned gateway when its identity changes then client rejects the new identity`() {
        val trustStore = InMemoryPeerTrustStore()
        val client = protocol(trustStore)
        val firstGateway = protocol(InMemoryPeerTrustStore())
        val firstHello = client.beginRequest(CLIENT_ID, "https://example.com/first")
        val firstAcknowledgement = firstGateway.acceptHello(firstHello.payload, CLIENT_ID, GATEWAY_ID)
        client.acceptHelloAcknowledgement(firstAcknowledgement.payload, GATEWAY_ID, CLIENT_ID)

        val replacementGateway = protocol(InMemoryPeerTrustStore())
        val secondHello = client.beginRequest(CLIENT_ID, "https://example.com/second")
        val secondAcknowledgement = replacementGateway.acceptHello(
            secondHello.payload,
            CLIENT_ID,
            GATEWAY_ID,
        )

        assertThrows(GeneralSecurityException::class.java) {
            client.acceptHelloAcknowledgement(secondAcknowledgement.payload, GATEWAY_ID, CLIENT_ID)
        }
    }

    @Test
    fun `given an encrypted request already consumed when replayed then gateway rejects it`() {
        val client = protocol(InMemoryPeerTrustStore())
        val gateway = protocol(InMemoryPeerTrustStore())
        val hello = client.beginRequest(CLIENT_ID, "https://example.com")
        val acknowledgement = gateway.acceptHello(hello.payload, CLIENT_ID, GATEWAY_ID)
        val request = client.acceptHelloAcknowledgement(
            acknowledgement.payload,
            GATEWAY_ID,
            CLIENT_ID,
        )

        gateway.decryptRequest(request.payload, CLIENT_ID, GATEWAY_ID)

        assertThrows(GeneralSecurityException::class.java) {
            gateway.decryptRequest(request.payload, CLIENT_ID, GATEWAY_ID)
        }
    }

    @Test
    fun `given an encrypted response already consumed when replayed then client rejects it`() {
        val exchange = completedRequest()
        val response = exchange.gateway.encryptResponse(
            exchange.requestId,
            CLIENT_ID,
            GATEWAY_ID,
            "response",
        )

        exchange.client.decryptResponse(response.payload, GATEWAY_ID, CLIENT_ID)

        assertThrows(GeneralSecurityException::class.java) {
            exchange.client.decryptResponse(response.payload, GATEWAY_ID, CLIENT_ID)
        }
    }

    @Test
    fun `given a modified encrypted request when gateway decrypts it then authentication fails`() {
        val client = protocol(InMemoryPeerTrustStore())
        val gateway = protocol(InMemoryPeerTrustStore())
        val hello = client.beginRequest(CLIENT_ID, "https://example.com")
        val acknowledgement = gateway.acceptHello(hello.payload, CLIENT_ID, GATEWAY_ID)
        val request = client.acceptHelloAcknowledgement(
            acknowledgement.payload,
            GATEWAY_ID,
            CLIENT_ID,
        )

        assertThrows(GeneralSecurityException::class.java) {
            gateway.decryptRequest(tamper(request.payload), CLIENT_ID, GATEWAY_ID)
        }
    }

    @Test
    fun `given an expired handshake when client accepts acknowledgement then it is rejected`() {
        val clock = MutableClock()
        val client = protocol(InMemoryPeerTrustStore(), clock)
        val gateway = protocol(InMemoryPeerTrustStore(), clock)
        val hello = client.beginRequest(CLIENT_ID, "https://example.com")
        val acknowledgement = gateway.acceptHello(hello.payload, CLIENT_ID, GATEWAY_ID)
        clock.now = SecureMeshProtocol.SESSION_TTL_MILLIS + 1

        assertThrows(GeneralSecurityException::class.java) {
            client.acceptHelloAcknowledgement(acknowledgement.payload, GATEWAY_ID, CLIENT_ID)
        }
    }

    private fun completedRequest(): CompletedRequest {
        val client = protocol(InMemoryPeerTrustStore())
        val gateway = protocol(InMemoryPeerTrustStore())
        val hello = client.beginRequest(CLIENT_ID, "https://example.com")
        val acknowledgement = gateway.acceptHello(hello.payload, CLIENT_ID, GATEWAY_ID)
        val request = client.acceptHelloAcknowledgement(
            acknowledgement.payload,
            GATEWAY_ID,
            CLIENT_ID,
        )
        val decrypted = gateway.decryptRequest(request.payload, CLIENT_ID, GATEWAY_ID)
        return CompletedRequest(client, gateway, decrypted.requestId)
    }

    private fun protocol(
        trustStore: MeshPeerTrustStore,
        clock: MeshClock = MeshClock { System.currentTimeMillis() },
    ): SecureMeshProtocol = SecureMeshProtocol(
        KeysetHandle.generateNew(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM),
        KeysetHandle.generateNew(SignatureKeyTemplates.ECDSA_P256),
        trustStore,
        clock,
    )

    private fun tamper(message: String): String {
        val mid = message.length - 10
        val replacement = if (message[mid] == 'A') 'B' else 'A'
        return message.substring(0, mid) + replacement + message.substring(mid + 1)
    }

    private data class CompletedRequest(
        val client: SecureMeshProtocol,
        val gateway: SecureMeshProtocol,
        val requestId: String,
    )

    private class MutableClock(var now: Long = 0) : MeshClock {
        override fun nowMillis(): Long = now
    }

    private class InMemoryPeerTrustStore : MeshPeerTrustStore {
        private val fingerprints = mutableMapOf<String, ByteArray>()

        override fun trustOrVerify(peerId: String, publicIdentityKeyset: ByteArray) {
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicIdentityKeyset)
            val existing = fingerprints[peerId]
            if (existing == null) {
                fingerprints[peerId] = fingerprint
            } else if (!MessageDigest.isEqual(existing, fingerprint)) {
                throw GeneralSecurityException("Gateway identity changed")
            }
        }
    }

    companion object {
        private const val CLIENT_ID = "11"
        private const val GATEWAY_ID = "20"

        @JvmStatic
        @BeforeAll
        fun registerTink() {
            TinkConfig.register()
        }
    }
}