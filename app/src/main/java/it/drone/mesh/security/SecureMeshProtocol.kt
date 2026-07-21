package it.drone.mesh.security

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import com.google.crypto.tink.subtle.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom

fun interface MeshClock {
    fun nowMillis(): Long
}

interface MeshPeerTrustStore {
    @Throws(GeneralSecurityException::class)
    fun trustOrVerify(peerId: String, publicIdentityKeyset: ByteArray)
}

enum class SecureMeshMessageType {
    HELLO,
    HELLO_ACKNOWLEDGEMENT,
    REQUEST,
    RESPONSE,
    UNKNOWN,
}

class SecureMeshProtocol @JvmOverloads constructor(
    private val localHybridPrivateKeyset: KeysetHandle,
    private val localSigningPrivateKeyset: KeysetHandle,
    private val trustStore: MeshPeerTrustStore,
    private val clock: MeshClock = MeshClock { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val secureRandom = SecureRandom()
    private val pendingClients = mutableMapOf<String, PendingClient>()
    private val gatewaySessions = mutableMapOf<String, GatewaySession>()
    private val clientSessions = mutableMapOf<String, ClientSession>()

    @Throws(GeneralSecurityException::class)
    fun beginRequest(clientId: String, plaintext: String): OutboundMessage = synchronized(lock) {
        validateEndpointId(clientId)
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        if (plaintextBytes.isEmpty() || plaintextBytes.size > MAX_PLAINTEXT_BYTES) {
            throw GeneralSecurityException("Request payload has an invalid size")
        }
        removeExpiredLocked()
        ensureCapacity(pendingClients.size)

        val requestId = newRequestId()
        val responsePrivateKeyset = KeysetHandle.generateNew(
            HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM,
        )
        val responsePublicKeyset = serializePublicKeyset(responsePrivateKeyset)
        pendingClients[requestId] = PendingClient(
            clientId = clientId,
            plaintext = plaintextBytes,
            responsePrivateKeyset = responsePrivateKeyset,
            createdAtMillis = clock.nowMillis(),
        )
        OutboundMessage(
            requestId,
            listOf(PROTOCOL, HELLO, requestId, encode(responsePublicKeyset)).joinToString(SEPARATOR),
        )
    }

    @Throws(GeneralSecurityException::class)
    fun acceptHello(payload: String, clientId: String, gatewayId: String): OutboundMessage =
        synchronized(lock) {
            validateEndpointId(clientId)
            validateEndpointId(gatewayId)
            removeExpiredLocked()
            ensureCapacity(gatewaySessions.size)
            val parts = parse(payload, HELLO, HELLO_PARTS)
            val requestId = parts[REQUEST_ID_INDEX]
            if (gatewaySessions.containsKey(requestId)) {
                throw GeneralSecurityException("Duplicate secure request")
            }

            val clientResponsePublicBytes = decode(parts[3], MAX_PUBLIC_KEYSET_BYTES)
            val clientResponsePublicKeyset = parsePublicKeyset(clientResponsePublicBytes)
            val clientResponseEncrypt = clientResponsePublicKeyset.getPrimitive(
                RegistryConfiguration.get(),
                HybridEncrypt::class.java,
            )
            val gatewayPublicBytes = serializePublicKeyset(localHybridPrivateKeyset)
            val identityPublicBytes = serializePublicKeyset(localSigningPrivateKeyset)
            val transcript = acknowledgementTranscript(
                requestId,
                clientId,
                gatewayId,
                parts[3],
                encode(gatewayPublicBytes),
                encode(identityPublicBytes),
            )
            val signature = localSigningPrivateKeyset.getPrimitive(
                RegistryConfiguration.get(),
                PublicKeySign::class.java,
            ).sign(transcript)

            gatewaySessions[requestId] = GatewaySession(
                clientId = clientId,
                gatewayId = gatewayId,
                clientResponseEncrypt = clientResponseEncrypt,
                createdAtMillis = clock.nowMillis(),
            )
            OutboundMessage(
                requestId,
                listOf(
                    PROTOCOL,
                    HELLO_ACKNOWLEDGEMENT,
                    requestId,
                    encode(gatewayPublicBytes),
                    encode(identityPublicBytes),
                    encode(signature),
                ).joinToString(SEPARATOR),
            )
        }

    @Throws(GeneralSecurityException::class)
    fun acceptHelloAcknowledgement(
        payload: String,
        gatewayId: String,
        clientId: String,
    ): OutboundMessage = synchronized(lock) {
        validateEndpointId(clientId)
        validateEndpointId(gatewayId)
        removeExpiredLocked()
        val parts = parse(payload, HELLO_ACKNOWLEDGEMENT, ACKNOWLEDGEMENT_PARTS)
        val requestId = parts[REQUEST_ID_INDEX]
        val pending = pendingClients[requestId]
            ?: throw GeneralSecurityException("Unknown or expired secure request")
        if (pending.clientId != clientId) {
            throw GeneralSecurityException("Secure request endpoint mismatch")
        }

        val gatewayPublicBytes = decode(parts[3], MAX_PUBLIC_KEYSET_BYTES)
        val identityPublicBytes = decode(parts[4], MAX_PUBLIC_KEYSET_BYTES)
        val signature = decode(parts[5], MAX_SIGNATURE_BYTES)
        val responsePublicBytes = serializePublicKeyset(pending.responsePrivateKeyset)
        val transcript = acknowledgementTranscript(
            requestId,
            clientId,
            gatewayId,
            encode(responsePublicBytes),
            parts[3],
            parts[4],
        )
        parsePublicKeyset(identityPublicBytes).getPrimitive(
            RegistryConfiguration.get(),
            PublicKeyVerify::class.java,
        ).verify(signature, transcript)
        trustStore.trustOrVerify(gatewayId, identityPublicBytes)

        val requestContext = encryptionContext(REQUEST, requestId, clientId, gatewayId)
        val ciphertext = parsePublicKeyset(gatewayPublicBytes).getPrimitive(
            RegistryConfiguration.get(),
            HybridEncrypt::class.java,
        ).encrypt(pending.plaintext, requestContext)
        pendingClients.remove(requestId)
        clientSessions[requestId] = ClientSession(
            clientId = clientId,
            gatewayId = gatewayId,
            responsePrivateKeyset = pending.responsePrivateKeyset,
            createdAtMillis = clock.nowMillis(),
        )
        OutboundMessage(
            requestId,
            listOf(PROTOCOL, REQUEST, requestId, encode(ciphertext)).joinToString(SEPARATOR),
        )
    }

    @Throws(GeneralSecurityException::class)
    fun decryptRequest(payload: String, clientId: String, gatewayId: String): DecryptedMessage =
        synchronized(lock) {
            validateEndpointId(clientId)
            validateEndpointId(gatewayId)
            removeExpiredLocked()
            val parts = parse(payload, REQUEST, ENCRYPTED_MESSAGE_PARTS)
            val requestId = parts[REQUEST_ID_INDEX]
            val session = gatewaySessions[requestId]
                ?: throw GeneralSecurityException("Unknown, expired, or replayed secure request")
            if (session.clientId != clientId || session.gatewayId != gatewayId || session.requestConsumed) {
                throw GeneralSecurityException("Secure request endpoint mismatch or replay")
            }
            val ciphertext = decode(parts[3], MAX_CIPHERTEXT_BYTES)
            val plaintext = localHybridPrivateKeyset.getPrimitive(
                RegistryConfiguration.get(),
                HybridDecrypt::class.java,
            ).decrypt(ciphertext, encryptionContext(REQUEST, requestId, clientId, gatewayId))
            if (plaintext.isEmpty() || plaintext.size > MAX_PLAINTEXT_BYTES) {
                throw GeneralSecurityException("Decrypted request has an invalid size")
            }
            session.requestConsumed = true
            DecryptedMessage(requestId, String(plaintext, StandardCharsets.UTF_8))
        }

    @Throws(GeneralSecurityException::class)
    fun encryptResponse(
        requestId: String,
        clientId: String,
        gatewayId: String,
        plaintext: String,
    ): OutboundMessage = synchronized(lock) {
        validateRequestId(requestId)
        validateEndpointId(clientId)
        validateEndpointId(gatewayId)
        removeExpiredLocked()
        val session = gatewaySessions[requestId]
            ?: throw GeneralSecurityException("Unknown, expired, or completed secure request")
        if (session.clientId != clientId || session.gatewayId != gatewayId || !session.requestConsumed) {
            throw GeneralSecurityException("Secure response endpoint mismatch")
        }
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        if (plaintextBytes.size > MAX_PLAINTEXT_BYTES) {
            throw GeneralSecurityException("Response payload is too large")
        }
        val ciphertext = session.clientResponseEncrypt.encrypt(
            plaintextBytes,
            encryptionContext(RESPONSE, requestId, clientId, gatewayId),
        )
        gatewaySessions.remove(requestId)
        OutboundMessage(
            requestId,
            listOf(PROTOCOL, RESPONSE, requestId, encode(ciphertext)).joinToString(SEPARATOR),
        )
    }

    @Throws(GeneralSecurityException::class)
    fun decryptResponse(payload: String, gatewayId: String, clientId: String): DecryptedMessage =
        synchronized(lock) {
            validateEndpointId(clientId)
            validateEndpointId(gatewayId)
            removeExpiredLocked()
            val parts = parse(payload, RESPONSE, ENCRYPTED_MESSAGE_PARTS)
            val requestId = parts[REQUEST_ID_INDEX]
            val session = clientSessions[requestId]
                ?: throw GeneralSecurityException("Unknown, expired, or replayed secure response")
            if (session.clientId != clientId || session.gatewayId != gatewayId) {
                throw GeneralSecurityException("Secure response endpoint mismatch")
            }
            val plaintext = session.responsePrivateKeyset.getPrimitive(
                RegistryConfiguration.get(),
                HybridDecrypt::class.java,
            ).decrypt(
                decode(parts[3], MAX_CIPHERTEXT_BYTES),
                encryptionContext(RESPONSE, requestId, clientId, gatewayId),
            )
            if (plaintext.size > MAX_PLAINTEXT_BYTES) {
                throw GeneralSecurityException("Decrypted response is too large")
            }
            clientSessions.remove(requestId)
            DecryptedMessage(requestId, String(plaintext, StandardCharsets.UTF_8))
        }

    private fun removeExpiredLocked() {
        val oldestAllowed = clock.nowMillis() - SESSION_TTL_MILLIS
        pendingClients.entries.removeAll { it.value.createdAtMillis < oldestAllowed }
        gatewaySessions.entries.removeAll { it.value.createdAtMillis < oldestAllowed }
        clientSessions.entries.removeAll { it.value.createdAtMillis < oldestAllowed }
    }

    private fun parse(payload: String, expectedType: String, expectedParts: Int): List<String> {
        if (payload.length > MAX_WIRE_MESSAGE_CHARS) {
            throw GeneralSecurityException("Secure message is too large")
        }
        val parts = payload.split(SEPARATOR)
        if (parts.size != expectedParts || parts[0] != PROTOCOL || parts[1] != expectedType) {
            throw GeneralSecurityException("Malformed secure message")
        }
        validateRequestId(parts[REQUEST_ID_INDEX])
        return parts
    }

    private fun newRequestId(): String {
        repeat(MAX_REQUEST_ID_ATTEMPTS) {
            val random = ByteArray(REQUEST_ID_BYTES).also(secureRandom::nextBytes)
            val requestId = encode(random)
            if (!pendingClients.containsKey(requestId) &&
                !gatewaySessions.containsKey(requestId) &&
                !clientSessions.containsKey(requestId)
            ) {
                return requestId
            }
        }
        throw GeneralSecurityException("Unable to allocate a unique request ID")
    }

    private fun ensureCapacity(size: Int) {
        if (size >= MAX_CONCURRENT_SESSIONS) {
            throw GeneralSecurityException("Too many concurrent secure requests")
        }
    }

    private fun validateRequestId(requestId: String) {
        if (!REQUEST_ID_PATTERN.matches(requestId)) {
            throw GeneralSecurityException("Invalid secure request ID")
        }
    }

    private fun validateEndpointId(endpointId: String) {
        if (!ENDPOINT_ID_PATTERN.matches(endpointId)) {
            throw GeneralSecurityException("Invalid mesh endpoint ID")
        }
    }

    private fun acknowledgementTranscript(
        requestId: String,
        clientId: String,
        gatewayId: String,
        clientPublicKey: String,
        gatewayPublicKey: String,
        identityPublicKey: String,
    ): ByteArray = listOf(
        PROTOCOL,
        HELLO_ACKNOWLEDGEMENT,
        requestId,
        clientId,
        gatewayId,
        clientPublicKey,
        gatewayPublicKey,
        identityPublicKey,
    ).joinToString("|").toByteArray(StandardCharsets.UTF_8)

    private fun encryptionContext(
        type: String,
        requestId: String,
        clientId: String,
        gatewayId: String,
    ): ByteArray = listOf(PROTOCOL, type, requestId, clientId, gatewayId)
        .joinToString("|")
        .toByteArray(StandardCharsets.UTF_8)

    private fun serializePublicKeyset(privateKeyset: KeysetHandle): ByteArray =
        TinkProtoKeysetFormat.serializeKeysetWithoutSecret(privateKeyset.publicKeysetHandle)

    private fun parsePublicKeyset(serialized: ByteArray): KeysetHandle =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(serialized)

    private fun encode(value: ByteArray): String = Base64.encodeToString(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun decode(value: String, maxBytes: Int): ByteArray {
        if (value.isEmpty() || value.length > encodedLength(maxBytes)) {
            throw GeneralSecurityException("Invalid encoded secure-message field")
        }
        return try {
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).also {
                if (it.isEmpty() || it.size > maxBytes) {
                    throw GeneralSecurityException("Invalid decoded secure-message field")
                }
            }
        } catch (exception: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid Base64 in secure message", exception)
        }
    }

    private fun encodedLength(bytes: Int): Int = ((bytes + 2) / 3) * 4

    data class OutboundMessage(val requestId: String, val payload: String)

    data class DecryptedMessage(val requestId: String, val plaintext: String)

    private data class PendingClient(
        val clientId: String,
        val plaintext: ByteArray,
        val responsePrivateKeyset: KeysetHandle,
        val createdAtMillis: Long,
    )

    private data class GatewaySession(
        val clientId: String,
        val gatewayId: String,
        val clientResponseEncrypt: HybridEncrypt,
        val createdAtMillis: Long,
        var requestConsumed: Boolean = false,
    )

    private data class ClientSession(
        val clientId: String,
        val gatewayId: String,
        val responsePrivateKeyset: KeysetHandle,
        val createdAtMillis: Long,
    )

    companion object {
        const val SESSION_TTL_MILLIS = 2 * 60 * 1000L
        const val PROTOCOL_PREFIX = "ML1."

        private const val PROTOCOL = "ML1"
        private const val HELLO = "H"
        private const val HELLO_ACKNOWLEDGEMENT = "A"
        private const val REQUEST = "Q"
        private const val RESPONSE = "R"
        private const val SEPARATOR = "."
        private const val REQUEST_ID_INDEX = 2
        private const val HELLO_PARTS = 4
        private const val ACKNOWLEDGEMENT_PARTS = 6
        private const val ENCRYPTED_MESSAGE_PARTS = 4
        private const val REQUEST_ID_BYTES = 16
        private const val MAX_REQUEST_ID_ATTEMPTS = 8
        private const val MAX_CONCURRENT_SESSIONS = 64
        private const val MAX_PUBLIC_KEYSET_BYTES = 4 * 1024
        private const val MAX_SIGNATURE_BYTES = 1024
        private const val MAX_PLAINTEXT_BYTES = 512 * 1024
        private const val MAX_CIPHERTEXT_BYTES = 600 * 1024
        private const val MAX_WIRE_MESSAGE_CHARS = 820 * 1024
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{16,64}")
        private val ENDPOINT_ID_PATTERN = Regex("[0-9]{1,3}")

        @JvmStatic
        fun isSecureMessage(payload: String): Boolean = payload.startsWith(PROTOCOL_PREFIX)

        @JvmStatic
        fun messageType(payload: String): SecureMeshMessageType = when {
            payload.startsWith("$PROTOCOL.$HELLO$SEPARATOR") -> SecureMeshMessageType.HELLO
            payload.startsWith("$PROTOCOL.$HELLO_ACKNOWLEDGEMENT$SEPARATOR") -> {
                SecureMeshMessageType.HELLO_ACKNOWLEDGEMENT
            }
            payload.startsWith("$PROTOCOL.$REQUEST$SEPARATOR") -> SecureMeshMessageType.REQUEST
            payload.startsWith("$PROTOCOL.$RESPONSE$SEPARATOR") -> SecureMeshMessageType.RESPONSE
            else -> SecureMeshMessageType.UNKNOWN
        }
    }
}