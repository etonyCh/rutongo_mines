package rw.rutongo.mmems.android.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import rw.rutongo.mmems.android.db.Outbox
import rw.rutongo.mmems.android.db.Pending_photo
import rw.rutongo.mmems.shared.api.ApiErrorView
import rw.rutongo.mmems.shared.api.BreakdownViewDto
import rw.rutongo.mmems.shared.api.ChecklistItemDto
import rw.rutongo.mmems.shared.api.EquipmentDto
import rw.rutongo.mmems.shared.api.ExpectedInspectionDto
import rw.rutongo.mmems.shared.api.InspectionDetailDto
import rw.rutongo.mmems.shared.api.LoginRequest
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.api.PhotoDto
import rw.rutongo.mmems.shared.api.RefreshRequest
import rw.rutongo.mmems.shared.api.SessionResponse
import rw.rutongo.mmems.shared.api.ShiftDto
import rw.rutongo.mmems.shared.api.SiteDto
import rw.rutongo.mmems.shared.api.StatusEventDto
import java.io.File

sealed interface ApiResult<out T> {
    data class Ok<T>(
        val value: T,
        val rawBody: String = "",
    ) : ApiResult<T>

    data class Conflict(
        val message: String,
        val currentState: String? = null,
    ) : ApiResult<Nothing>

    data class Invalid(
        val code: String,
        val message: String,
        val fieldErrors: Map<String, String> = emptyMap(),
    ) : ApiResult<Nothing>

    data class Retryable(
        val message: String,
    ) : ApiResult<Nothing>
}

interface MmemsApi {
    suspend fun login(
        username: String,
        password: String,
        deviceLabel: String,
    ): ApiResult<SessionResponse>

    suspend fun me(): ApiResult<MeView>

    suspend fun sites(): ApiResult<List<SiteDto>>

    suspend fun equipment(): ApiResult<List<EquipmentDto>>

    suspend fun shifts(): ApiResult<List<ShiftDto>>

    suspend fun checklist(): ApiResult<List<ChecklistItemDto>>

    suspend fun expected(): ApiResult<List<ExpectedInspectionDto>>

    suspend fun submitInspection(operation: Outbox): ApiResult<InspectionDetailDto>

    suspend fun submitStatus(operation: Outbox): ApiResult<StatusEventDto>

    suspend fun breakdowns(): ApiResult<List<BreakdownViewDto>>

    suspend fun submitBreakdown(operation: Outbox): ApiResult<BreakdownViewDto>

    suspend fun submitBreakdownTransition(
        operation: Outbox,
        breakdownId: String,
    ): ApiResult<BreakdownViewDto>

    suspend fun uploadPhoto(
        operation: Pending_photo,
        clientUuid: String,
    ): ApiResult<PhotoDto>
}

class KtorMmemsApi(
    private val tokens: TokenStore,
    private val settings: AppSettings,
    private val client: HttpClient = defaultClient(),
) : MmemsApi {
    private val refreshMutex = Mutex()

    override suspend fun login(
        username: String,
        password: String,
        deviceLabel: String,
    ): ApiResult<SessionResponse> =
        try {
            val response =
                client.post("${settings.apiBaseUrl}/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(username = username, password = password, deviceLabel = deviceLabel))
                }
            val result = parse<SessionResponse>(response)
            if (result is ApiResult.Ok) tokens.save(result.value)
            result
        } catch (e: Exception) {
            ApiResult.Retryable(e.message ?: "Login failed.")
        }

    override suspend fun me(): ApiResult<MeView> = jwtGet("/api/v1/me")

    override suspend fun sites(): ApiResult<List<SiteDto>> = jwtGet("/api/v1/sites")

    override suspend fun equipment(): ApiResult<List<EquipmentDto>> = jwtGet("/api/v1/equipment?limit=200")

    override suspend fun shifts(): ApiResult<List<ShiftDto>> = jwtGet("/api/v1/shifts")

    override suspend fun checklist(): ApiResult<List<ChecklistItemDto>> = jwtGet("/api/v1/checklist-items?active=true")

    override suspend fun expected(): ApiResult<List<ExpectedInspectionDto>> = jwtGet("/api/v1/inspections/expected")

    override suspend fun submitInspection(operation: Outbox): ApiResult<InspectionDetailDto> = jwtPost("/api/v1/inspections", operation.payload) { header("Idempotency-Key", operation.client_uuid) }

    override suspend fun submitStatus(operation: Outbox): ApiResult<StatusEventDto> =
        jwtPost("/api/v1/equipment/${operation.equipment_id}/status", operation.payload) {
            header("Idempotency-Key", operation.client_uuid)
            operation.if_match?.let { header(HttpHeaders.IfMatch, it.toString()) }
        }

    override suspend fun breakdowns(): ApiResult<List<BreakdownViewDto>> = jwtGet("/api/v1/breakdowns?limit=200")

    override suspend fun submitBreakdown(operation: Outbox): ApiResult<BreakdownViewDto> =
        jwtPost("/api/v1/breakdowns", operation.payload) {
            header("Idempotency-Key", operation.client_uuid)
        }

    override suspend fun submitBreakdownTransition(
        operation: Outbox,
        breakdownId: String,
    ): ApiResult<BreakdownViewDto> =
        jwtPost("/api/v1/breakdowns/$breakdownId/transition", operation.payload) {
            header("Idempotency-Key", operation.client_uuid)
            operation.if_match?.let { header(HttpHeaders.IfMatch, it.toString()) }
        }

    override suspend fun uploadPhoto(
        operation: Pending_photo,
        clientUuid: String,
    ): ApiResult<PhotoDto> {
        val file = File(operation.file_path)
        if (!file.exists()) return ApiResult.Invalid("PHOTO_FILE_MISSING", "The captured photo file is missing.")
        return try {
            val response =
                authorized { token ->
                    client.submitFormWithBinaryData(
                        url = "${settings.apiBaseUrl}/api/v1/photos",
                        formData =
                            formData {
                                append("siteId", operation.site_id)
                                append("targetType", operation.target_type)
                                append("targetId", operation.target_id)
                                append("kind", "EVIDENCE")
                                append("photoId", operation.id)
                                append("clientUuid", clientUuid)
                                append(
                                    "file",
                                    file.readBytes(),
                                    Headers.build {
                                        append(HttpHeaders.ContentType, operation.mime)
                                        append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                                    },
                                )
                            },
                    ) {
                        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    }
                }
            parse<PhotoDto>(response)
        } catch (e: Exception) {
            ApiResult.Retryable(e.message ?: "Photo upload failed.")
        }
    }

    private suspend inline fun <reified T> jwtGet(path: String): ApiResult<T> =
        try {
            val response = authorized { token -> client.get("${settings.apiBaseUrl}$path") { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } } }
            parse<T>(response)
        } catch (e: Exception) {
            ApiResult.Retryable(e.message ?: "The server is unreachable.")
        }

    private suspend inline fun <reified T> jwtPost(
        path: String,
        payload: String,
        crossinline extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): ApiResult<T> =
        try {
            val response =
                authorized { token ->
                    client.post("${settings.apiBaseUrl}$path") {
                        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                        header(HttpHeaders.ContentType, ContentType.Application.Json)
                        setBody(payload)
                        extra()
                    }
                }
            parse<T>(response)
        } catch (e: Exception) {
            ApiResult.Retryable(e.message ?: "The server is unreachable.")
        }

    private suspend fun authorized(block: suspend (String?) -> HttpResponse): HttpResponse {
        val first = block(tokens.accessToken())
        if (first.status != HttpStatusCode.Unauthorized) return first
        val refreshed = refreshMutex.withLock { refreshIfNeeded() }
        return if (refreshed) block(tokens.accessToken()) else first
    }

    private suspend fun refreshIfNeeded(): Boolean {
        val refresh = tokens.refreshToken() ?: return false
        val response =
            runCatching {
                client.post("${settings.apiBaseUrl}/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken = refresh))
                }
            }.getOrNull() ?: return false
        if (!response.status.isSuccess()) return false
        val session = runCatching { response.body<SessionResponse>() }.getOrNull() ?: return false
        tokens.save(session)
        return true
    }

    private suspend inline fun <reified T> parse(response: HttpResponse): ApiResult<T> {
        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        return when {
            response.status.isSuccess() -> ApiResult.Ok(response.body<T>(), text)
            response.status == HttpStatusCode.Conflict ->
                ApiResult.Conflict(
                    message = runCatching { Json.decodeFromString(ApiErrorView.serializer(), text).message }.getOrDefault(text),
                    currentState = text,
                )
            response.status.value in 400..499 -> {
                val error = runCatching { Json.decodeFromString(ApiErrorView.serializer(), text) }.getOrNull()
                ApiResult.Invalid(error?.code ?: "REQUEST_REJECTED", error?.message ?: text.ifBlank { "The request was rejected." })
            }
            else -> ApiResult.Retryable("HTTP ${response.status.value}: ${text.take(300)}")
        }
    }

    companion object {
        fun defaultClient(): HttpClient =
            HttpClient(OkHttp) {
                expectSuccess = false
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 15_000
                }
                install(Logging) { level = LogLevel.NONE }
                defaultRequest { header(HttpHeaders.Accept, ContentType.Application.Json) }
            }
    }
}
