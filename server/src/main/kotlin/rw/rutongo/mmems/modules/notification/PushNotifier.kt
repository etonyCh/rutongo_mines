@file:Suppress("MagicNumber")

package rw.rutongo.mmems.modules.notification

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface PushNotifier {
    fun send(
        tokens: Collection<String>,
        title: String,
        body: String,
        data: Map<String, String>,
    )
}

class FcmPushNotifier(
    private val serverKey: String? = System.getenv("FCM_SERVER_KEY"),
    private val endpoint: String = "https://fcm.googleapis.com/fcm/send",
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build(),
) : PushNotifier {
    private val log = LoggerFactory.getLogger(FcmPushNotifier::class.java)

    override fun send(
        tokens: Collection<String>,
        title: String,
        body: String,
        data: Map<String, String>,
    ) {
        if (serverKey.isNullOrBlank() || tokens.isEmpty()) return
        val payload =
            buildJsonObject {
                putJsonArray("registration_ids") { tokens.forEach { add(it) } }
                putJsonObject("notification") {
                    put("title", title)
                    put("body", body)
                }
                putJsonObject("data") { data.forEach { (key, value) -> put(key, value) } }
            }.toString()
        val request =
            HttpRequest
                .newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "key=$serverKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
        client
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .exceptionally {
                log.warn("FCM notification delivery failed", it)
                null
            }
    }
}
