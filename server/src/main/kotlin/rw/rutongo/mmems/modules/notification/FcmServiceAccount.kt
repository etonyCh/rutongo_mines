package rw.rutongo.mmems.modules.notification

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class FcmServiceAccount(
    @SerialName("project_id") val projectId: String,
    @SerialName("client_email") val clientEmail: String,
    @SerialName("private_key") val privateKey: String,
    @SerialName("private_key_id") val privateKeyId: String? = null,
    @SerialName("token_uri") val tokenUri: String = DEFAULT_TOKEN_URI,
) {
    companion object {
        const val ENV = "FCM_CREDENTIALS"
        const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
        private val json = Json { ignoreUnknownKeys = true }

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): FcmServiceAccount? {
            val raw = environment[ENV]?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val content = if (raw.startsWith("{")) raw else Files.readString(Path.of(raw))
            return json.decodeFromString<FcmServiceAccount>(content)
        }
    }
}
