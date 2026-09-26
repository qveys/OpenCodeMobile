package org.opencodemobile.shared.testsupport.tls

import java.io.BufferedInputStream
import java.io.Closeable
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/** One HTTP request observed by [SelfSignedTlsServer]. */
public data class RecordedHttpRequest(
    public val method: String,
    public val path: String,
    public val headers: Map<String, List<String>>,
) {
    /** The `Authorization` header value if the request carried one, else null. */
    public val authorization: String?
        get() = headers.entries
            .firstOrNull { it.key.equals(HEADER_AUTHORIZATION, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}

/**
 * A tiny in-process TLS test peer that presents a caller-supplied self-signed
 * certificate and serves a minimal HTTP/1.1 `200 OK` response.
 *
 * It exists so the T1 real-handshake tests (`OPE-94`) can drive the platform
 * pinning engine against an actual handshake, on-device, with no external
 * service. Every request is recorded in [requests] so a test can prove that a
 * request carrying the `Authorization` header was **never** sent over an
 * unverified connection — the server simply never sees it.
 *
 * Test-only. The embedded key material is a throwaway fixture, not a secret.
 */
public class SelfSignedTlsServer(
    certPem: String,
    privateKeyPkcs8Pem: String,
) : Closeable {

    private val serverSocket: SSLServerSocket
    private val observedRequests = CopyOnWriteArrayList<RecordedHttpRequest>()

    @Volatile
    private var running: Boolean = true

    /** The ephemeral port this server bound to on `127.0.0.1`. */
    public val port: Int

    /** `https://127.0.0.1:<port>`, the base URL a client should target. */
    public val baseUrl: String
        get() = "https://127.0.0.1:$port"

    init {
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers(certPem, privateKeyPkcs8Pem), null, null)
        val socket = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        socket.needClientAuth = false
        serverSocket = socket
        port = socket.localPort
        thread(isDaemon = true, name = "t1-self-signed-tls-server") { acceptLoop() }
    }

    /** Snapshot of every request observed so far. */
    public fun requests(): List<RecordedHttpRequest> = observedRequests.toList()

    /** Authorization header values observed so far (must stay empty on the fail-closed path). */
    public fun authorizationHeaders(): List<String> = observedRequests.mapNotNull { it.authorization }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                serverSocket.accept()
            } catch (failure: Exception) {
                if (running) continue else break
            }
            thread(isDaemon = true, name = "t1-self-signed-tls-server-conn") { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                val reader = input.bufferedReader(Charsets.ISO_8859_1)

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: ""

                val headers = LinkedHashMap<String, MutableList<String>>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        val name = line.substring(0, separator).trim()
                        val value = line.substring(separator + 1).trim()
                        headers.getOrPut(name) { mutableListOf() }.add(value)
                    }
                }

                observedRequests.add(RecordedHttpRequest(method, path, headers))

                val body = """{"healthy":true,"version":"t1-test"}"""
                val response = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                    append(body)
                }
                output.write(response.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (_: Exception) {
            // A client that aborts the handshake is the expected fail-closed
            // outcome; nothing to record and nothing to report.
        }
    }

    override fun close() {
        running = false
        try {
            serverSocket.close()
        } catch (_: Exception) {
        }
    }

    private fun keyManagers(certPem: String, privateKeyPkcs8Pem: String): Array<javax.net.ssl.KeyManager> {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certPem.byteInputStream()) as X509Certificate
        val privateKeyBytes = decodePem(privateKeyPkcs8Pem)
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null, null) }
        keyStore.setKeyEntry(KEY_ALIAS, privateKey, KEYSTORE_PASSWORD, arrayOf(certificate))
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, KEYSTORE_PASSWORD) }
            .keyManagers
    }

    private fun decodePem(pem: String): ByteArray {
        val body = pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString(separator = "")
            .filterNot { it.isWhitespace() }
        return Base64.getDecoder().decode(body)
    }

    private companion object {
        const val KEYSTORE_TYPE = "PKCS12"
        const val KEY_ALIAS = "t1-test"
        val KEYSTORE_PASSWORD = "t1test".toCharArray()
    }
}