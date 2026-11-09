package rw.rutongo.mmems.modules.notification

import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FcmPushNotifierTest {
    @Test
    fun `sends http v1 message to project endpoint with bearer token`() {
        val request = AtomicReference<CapturedRequest>()
        val received = CountDownLatch(1)
        val server =
            localServer { exchange ->
                request.set(
                    CapturedRequest(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.path,
                        authorization = exchange.requestHeaders.getFirst("Authorization"),
                        body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
                    ),
                )
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("{}".toByteArray()) }
                received.countDown()
            }
        try {
            val notifier =
                FcmPushNotifier(
                    credentials = FcmServiceAccount("project-1", "service@example.test", "ignored"),
                    tokenProvider = FcmAccessTokenProvider { "access-token" },
                    client = HttpClient.newHttpClient(),
                    endpointBase = "http://127.0.0.1:${server.address.port}",
                )

            notifier.send(listOf("device-1"), "Low stock", "Part below minimum", mapOf("type" to "LOW_STOCK"))

            assertTrue(received.await(5, TimeUnit.SECONDS))
            val captured = request.get()
            assertEquals("POST", captured.method)
            assertEquals("/v1/projects/project-1/messages:send", captured.path)
            assertEquals("Bearer access-token", captured.authorization)
            val message =
                Json
                    .parseToJsonElement(captured.body)
                    .jsonObject
                    .getValue("message")
                    .jsonObject
            assertEquals("device-1", message.getValue("token").jsonPrimitive.content)
            assertEquals(
                "Low stock",
                message
                    .getValue("notification")
                    .jsonObject
                    .getValue("title")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "LOW_STOCK",
                message
                    .getValue("data")
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `service account token provider signs assertion and caches token`() {
        val requests = AtomicInteger()
        val assertion = AtomicReference<String>()
        val server =
            localServer { exchange ->
                requests.incrementAndGet()
                val form = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                assertion.set(
                    URLDecoder.decode(
                        form.split("&").first { it.startsWith("assertion=") }.substringAfter("assertion="),
                        StandardCharsets.UTF_8,
                    ),
                )
                val body = """{"access_token":"oauth-token","expires_in":3600}"""
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
        try {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val encoded =
                Base64
                    .getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
                    .encodeToString(keyPair.private.encoded)
            val credentials =
                FcmServiceAccount(
                    projectId = "project-1",
                    clientEmail = "service@example.test",
                    privateKey = "-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----\n",
                    tokenUri = "http://127.0.0.1:${server.address.port}/token",
                )
            val provider = ServiceAccountFcmTokenProvider(credentials, HttpClient.newHttpClient()) { Instant.now() }

            assertEquals("oauth-token", provider.accessToken())
            assertEquals("oauth-token", provider.accessToken())
            assertEquals(1, requests.get())
            val jwt = SignedJWT.parse(assertion.get())
            assertTrue(jwt.verify(RSASSAVerifier(keyPair.public as RSAPublicKey)))
            assertEquals("service@example.test", jwt.jwtClaimsSet.issuer)
            assertEquals("http://127.0.0.1:${server.address.port}/token", jwt.jwtClaimsSet.audience.single())
            assertEquals("https://www.googleapis.com/auth/firebase.messaging", jwt.jwtClaimsSet.getStringClaim("scope"))
        } finally {
            server.stop(0)
        }
    }

    private fun localServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    handler(exchange)
                } finally {
                    exchange.close()
                }
            }
            start()
        }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
    )
}
