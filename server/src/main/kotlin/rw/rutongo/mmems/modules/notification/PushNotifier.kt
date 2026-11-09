@file:Suppress("MagicNumber")

package rw.rutongo.mmems.modules.notification

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

interface PushNotifier {
    fun send(
        tokens: Collection<String>,
        title: String,
        body: String,
        data: Map<String, String>,
    )
}

fun interface FcmAccessTokenProvider {
    fun accessToken(): String
}

class ServiceAccountFcmTokenProvider(
    private val credentials: FcmServiceAccount,
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build(),
    private val now: () -> Instant = Instant::now,
) : FcmAccessTokenProvider {
    @Volatile
    private var cached: CachedToken? = null

    override fun accessToken(): String {
        val current = cached
        if (current != null && current.expiresAt.isAfter(now().plusSeconds(REFRESH_MARGIN_SECONDS))) {
            return current.value
        }
        return synchronized(this) {
            val refreshed = cached
            if (refreshed != null && refreshed.expiresAt.isAfter(now().plusSeconds(REFRESH_MARGIN_SECONDS))) {
                refreshed.value
            } else {
                requestToken().also { cached = it }.value
            }
        }
    }

    private fun requestToken(): CachedToken {
        val issuedAt = now()
        val assertion =
            SignedJWT(
                JWSHeader
                    .Builder(JWSAlgorithm.RS256)
                    .type(JOSEObjectType.JWT)
                    .keyID(credentials.privateKeyId)
                    .build(),
                JWTClaimsSet
                    .Builder()
                    .issuer(credentials.clientEmail)
                    .subject(credentials.clientEmail)
                    .audience(credentials.tokenUri)
                    .claim("scope", FCM_SCOPE)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plusSeconds(TOKEN_LIFETIME_SECONDS)))
                    .build(),
            ).apply {
                sign(RSASSASigner(privateKey()))
            }.serialize()

        val form =
            "grant_type=${URLEncoder.encode(JWT_BEARER_GRANT, StandardCharsets.UTF_8)}" +
                "&assertion=${URLEncoder.encode(assertion, StandardCharsets.UTF_8)}"
        val request =
            HttpRequest
                .newBuilder(URI.create(credentials.tokenUri))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "FCM token request failed with status ${response.statusCode()}" }
        val body = Json.parseToJsonElement(response.body()).jsonObject
        val token = body["access_token"]?.jsonPrimitive?.content ?: error("FCM token response did not contain access_token")
        val expiresIn = body["expires_in"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TOKEN_LIFETIME_SECONDS
        return CachedToken(token, issuedAt.plusSeconds(expiresIn))
    }

    private fun privateKey(): PrivateKey {
        val der =
            Base64
                .getDecoder()
                .decode(
                    credentials.privateKey
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replace(WHITESPACE, ""),
                )
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private data class CachedToken(
        val value: String,
        val expiresAt: Instant,
    )

    private companion object {
        const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        const val TOKEN_LIFETIME_SECONDS = 3600L
        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L
        const val REFRESH_MARGIN_SECONDS = 60L
        val WHITESPACE = Regex("\\s+")
    }
}

class FcmPushNotifier(
    private val credentials: FcmServiceAccount? = FcmServiceAccount.fromEnvironment(),
    private val tokenProvider: FcmAccessTokenProvider? = credentials?.let { ServiceAccountFcmTokenProvider(it) },
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build(),
    private val endpointBase: String = "https://fcm.googleapis.com",
) : PushNotifier {
    private val log = LoggerFactory.getLogger(FcmPushNotifier::class.java)

    override fun send(
        tokens: Collection<String>,
        title: String,
        body: String,
        data: Map<String, String>,
    ) {
        val account = credentials
        val accessToken = tokenProvider
        if (account == null || accessToken == null || tokens.isEmpty()) return
        val bearer =
            runCatching { accessToken.accessToken() }
                .onFailure { log.warn("FCM access token request failed", it) }
                .getOrNull()
        if (bearer == null) return
        tokens.forEach { deviceToken ->
            val payload =
                buildJsonObject {
                    putJsonObject("message") {
                        put("token", deviceToken)
                        putJsonObject("notification") {
                            put("title", title)
                            put("body", body)
                        }
                        putJsonObject("data") {
                            data.forEach { (key, value) -> put(key, value) }
                        }
                    }
                }.toString()
            val request =
                HttpRequest
                    .newBuilder(URI.create("${endpointBase.trimEnd('/')}/v1/projects/${account.projectId}/messages:send"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer $bearer")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build()
            client
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept { response ->
                    if (response.statusCode() !in 200..299) {
                        log.warn("FCM delivery failed with status {}", response.statusCode())
                    }
                }.exceptionally {
                    log.warn("FCM delivery failed", it)
                    null
                }
        }
    }
}
