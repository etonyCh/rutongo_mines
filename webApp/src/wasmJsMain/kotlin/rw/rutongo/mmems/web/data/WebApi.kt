@file:Suppress("TooGenericExceptionCaught", "MagicNumber", "TooManyFunctions")
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package rw.rutongo.mmems.web.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rw.rutongo.mmems.shared.api.ApiErrorView
import rw.rutongo.mmems.shared.api.DashboardDto
import rw.rutongo.mmems.shared.api.EquipmentDto
import rw.rutongo.mmems.shared.api.ExportDatasetDto
import rw.rutongo.mmems.shared.api.LoginRequest
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.api.NotificationRuleDto
import rw.rutongo.mmems.shared.api.NotificationRuleUpdateDto
import rw.rutongo.mmems.shared.api.RefreshRequest
import rw.rutongo.mmems.shared.api.ReportViewDto
import rw.rutongo.mmems.shared.api.SessionResponse
import rw.rutongo.mmems.shared.api.SiteDto
import kotlin.io.encoding.Base64

@Serializable private data class CreateUserBody(
    val username: String,
    val fullName: String,
    val employeeId: String,
    val role: String,
    val initialPassword: String,
    val siteIds: List<String> = emptyList(),
)

@Serializable private data class SetUserActiveBody(
    val active: Boolean,
)

@Serializable private data class ChangePasswordBody(
    val currentPassword: String,
    val newPassword: String,
)

sealed interface WebResult<out T> {
    data class Success<T>(
        val value: T,
    ) : WebResult<T>

    data class Failure(
        val code: String,
        val message: String,
        val status: Int? = null,
    ) : WebResult<Nothing>
}

@Serializable data class WebUser(
    val id: String,
    val username: String,
    val fullName: String,
    val employeeId: String,
    val role: String,
    val active: Boolean,
    val siteIds: List<String> = emptyList(),
    val version: Long = 0,
)

@Serializable data class AuditEventRow(
    val actorId: String? = null,
    val action: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val occurredAt: String? = null,
    val metadata: String? = null,
    val previousHash: String? = null,
    val eventHash: String? = null,
)

@Serializable data class ImportProfile(
    val id: String,
    val name: String,
    val kind: String,
    val sheetName: String? = null,
    val columnMapping: Map<String, String> = emptyMap(),
    val version: Long = 0,
)

@Serializable data class ImportBatch(
    val id: String,
    val state: String,
    val fileSha256: String,
    val newCount: Int = 0,
    val updatedCount: Int = 0,
    val unchangedCount: Int = 0,
    val rejectedCount: Int = 0,
    val newEquipmentTypes: List<String> = emptyList(),
)

@Serializable data class ImportRow(
    val rowNumber: Int,
    val businessKey: String? = null,
    val state: String,
    val rejectionReason: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable data class ImportPreview(
    val batch: ImportBatch,
    val rows: List<ImportRow> = emptyList(),
    val nextRowNumber: Int? = null,
)

@JsFun(
    """
    () => {
      const scope = globalThis;
      if (scope.__rutongoFetchWrapped) return;
      const original = scope.fetch.bind(scope);
      scope.fetch = (input, init) => original(input, Object.assign({}, init || {}, { credentials: 'include' }));
      scope.__rutongoFetchWrapped = true;
    }
    """,
)
private external fun installFetchCredentials()

@JsFun(
    """
    (base64, filename, mime) => {
      const link = document.createElement('a');
      link.href = 'data:' + mime + ';base64,' + base64;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
    }
    """,
)
private external fun downloadBase64(
    base64: String,
    filename: String,
    mime: String,
)

@JsFun(
    """
    (callback) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.xlsx';
      input.onchange = async () => {
        const file = input.files && input.files[0];
        if (!file) return;
        const bytes = new Uint8Array(await file.arrayBuffer());
        let binary = '';
        for (let index = 0; index < bytes.length; index += 0x8000) {
          binary += String.fromCharCode.apply(null, bytes.subarray(index, index + 0x8000));
        }
        callback(file.name, btoa(binary));
      };
      input.click();
    }
    """,
)
external fun pickXlsxFile(callback: (fileName: String, base64: String) -> Unit)

@JsFun("() => new Date().toISOString()")
private external fun nowIso(): String

@JsFun("(days) => new Date(Date.now() - days * 86400000).toISOString()")
private external fun daysAgoIso(days: Int): String

class WebApi(
    baseUrl: String,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    private val base = baseUrl.trimEnd('/')
    private val csrf = "web-${randomToken()}"
    private val client: HttpClient

    var accessToken: String? = null
        private set

    init {
        installFetchCredentials()
        client =
            HttpClient(Js) {
                expectSuccess = false
                install(ContentNegotiation) { json(this@WebApi.json) }
            }
    }

    suspend fun login(
        username: String,
        password: String,
    ): WebResult<SessionResponse> {
        val request = LoginRequest(username.trim(), password, clientType = "WEB", deviceLabel = "Rutongo web")
        val response =
            runCatching { rawPost("/api/v1/auth/login", json.encodeToString(LoginRequest.serializer(), request)) }
                .getOrElse { return WebResult.Failure("NETWORK", it.message ?: "The server is unreachable.") }
        if (!response.status.isSuccess()) return parseFailure(response)
        val session = decode<SessionResponse>(response) ?: return invalidResponse(response)
        accessToken = session.accessToken
        return WebResult.Success(session)
    }

    suspend fun me(): WebResult<MeView> = authorized(HttpMethod.Get, "/api/v1/me")

    suspend fun sites(): WebResult<List<SiteDto>> = authorized(HttpMethod.Get, "/api/v1/sites")

    suspend fun equipment(): WebResult<List<EquipmentDto>> = authorized(HttpMethod.Get, "/api/v1/equipment?limit=200")

    suspend fun dashboard(
        kind: String,
        siteId: String? = null,
    ): WebResult<DashboardDto> {
        val query =
            buildString {
                append("?from=").append(daysAgoIso(7)).append("&to=").append(nowIso())
                if (!siteId.isNullOrBlank()) append("&site=").append(siteId)
            }
        return authorized(HttpMethod.Get, "/api/v1/dashboard/$kind$query")
    }

    suspend fun reports(
        siteId: String? = null,
        state: String? = null,
    ): WebResult<List<ReportViewDto>> {
        val query =
            buildString {
                append("?limit=100")
                if (!siteId.isNullOrBlank()) append("&siteId=").append(siteId)
                if (!state.isNullOrBlank()) append("&state=").append(state)
            }
        return authorized(HttpMethod.Get, "/api/v1/reports$query")
    }

    suspend fun users(): WebResult<List<WebUser>> = authorized(HttpMethod.Get, "/api/v1/users")

    suspend fun auditEvents(): WebResult<List<AuditEventRow>> = authorized(HttpMethod.Get, "/api/v1/audit-events?limit=200")

    suspend fun importProfiles(): WebResult<List<ImportProfile>> = authorized(HttpMethod.Get, "/api/v1/import-profiles")

    suspend fun importPreview(
        profileId: String,
        fileName: String,
        bytes: ByteArray,
    ): WebResult<ImportPreview> =
        authorizedRequest {
            client.post("$base/api/v1/imports") {
                accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("profileId", profileId)
                            append(
                                "file",
                                bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                },
                            )
                        },
                    ),
                )
            }
        }

    suspend fun importRows(id: String): WebResult<ImportPreview> = authorized(HttpMethod.Get, "/api/v1/imports/$id")

    suspend fun commitImport(
        id: String,
        applyUpdates: Boolean,
    ): WebResult<ImportBatch> =
        authorized(
            HttpMethod.Post,
            "/api/v1/imports/$id/commit",
            json.encodeToString(CommitImport.serializer(), CommitImport(applyUpdates)),
        )

    suspend fun createUser(
        username: String,
        fullName: String,
        employeeId: String,
        role: String,
        initialPassword: String,
        siteIds: List<String>,
    ): WebResult<WebUser> =
        authorized(
            HttpMethod.Post,
            "/api/v1/users",
            json.encodeToString(
                CreateUserBody.serializer(),
                CreateUserBody(username.trim(), fullName.trim(), employeeId.trim(), role, initialPassword, siteIds),
            ),
        )

    suspend fun setUserActive(
        id: String,
        active: Boolean,
    ): WebResult<Unit> = authorizedStatus(HttpMethod.Patch, "/api/v1/users/$id/active", json.encodeToString(SetUserActiveBody.serializer(), SetUserActiveBody(active)))

    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): WebResult<Unit> =
        authorizedStatus(
            HttpMethod.Post,
            "/api/v1/auth/change-password",
            json.encodeToString(ChangePasswordBody.serializer(), ChangePasswordBody(currentPassword, newPassword)),
        )

    suspend fun exportDatasets(): WebResult<List<ExportDatasetDto>> = authorized(HttpMethod.Get, "/api/v1/exports")

    suspend fun downloadExport(
        dataset: String,
        format: String,
        siteId: String? = null,
    ): WebResult<Unit> {
        val query = if (siteId.isNullOrBlank()) "" else "?site=$siteId"
        val result = authorizedRequest<ByteArray> { raw(HttpMethod.Get, "/api/v1/exports/$dataset.$format$query", token = accessToken) }
        return when (result) {
            is WebResult.Failure -> result
            is WebResult.Success -> {
                val extension = format.lowercase()
                val mime = if (extension == "csv") "text/csv" else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                downloadBase64(Base64.encode(result.value), "$dataset.$extension", mime)
                WebResult.Success(Unit)
            }
        }
    }

    suspend fun notificationRules(): WebResult<List<NotificationRuleDto>> = authorized(HttpMethod.Get, "/api/v1/notification-rules")

    suspend fun updateNotificationRule(
        type: String,
        update: NotificationRuleUpdateDto,
    ): WebResult<NotificationRuleDto> =
        authorized(
            HttpMethod.Put,
            "/api/v1/notification-rules/$type",
            json.encodeToString(NotificationRuleUpdateDto.serializer(), update),
        )

    suspend fun downloadReportPdf(
        reportId: String,
        versionNo: Int,
    ): WebResult<Unit> {
        val result =
            authorizedRequest<ByteArray> {
                raw(HttpMethod.Get, "/api/v1/reports/$reportId/pdf?version=$versionNo", token = accessToken)
            }
        return when (result) {
            is WebResult.Failure -> result
            is WebResult.Success -> {
                downloadBase64(Base64.encode(result.value), "report-$reportId-v$versionNo.pdf", "application/pdf")
                WebResult.Success(Unit)
            }
        }
    }

    suspend fun logout() {
        runCatching { rawPost("/api/v1/auth/logout", "{}") }
        accessToken = null
    }

    private suspend fun refresh(): Boolean {
        val request = RefreshRequest(clientType = "WEB", deviceLabel = "Rutongo web")
        val response =
            runCatching {
                client.post("$base/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    header("X-CSRF-Token", csrf)
                    header("X-Requested-With", "rutongo-mmems")
                    setBody(json.encodeToString(RefreshRequest.serializer(), request))
                }
            }.getOrNull() ?: return false
        if (!response.status.isSuccess()) return false
        val session = decode<SessionResponse>(response) ?: return false
        accessToken = session.accessToken
        return true
    }

    private suspend fun authorizedStatus(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): WebResult<Unit> =
        try {
            rawStatus(method, path, body, accessToken)
            WebResult.Success(Unit)
        } catch (error: StatusFailure) {
            error.failure
        } catch (error: Exception) {
            WebResult.Failure("NETWORK", error.message ?: "The server is unreachable.")
        }

    private suspend fun rawStatus(
        method: HttpMethod,
        path: String,
        body: String? = null,
        token: String? = null,
    ): HttpResponse {
        var response = raw(method, path, body, ContentType.Application.Json.toString(), token)
        if (response.status == HttpStatusCode.Unauthorized && refresh()) {
            response = raw(method, path, body, ContentType.Application.Json.toString(), accessToken)
        }
        if (!response.status.isSuccess()) throw StatusFailure(response.status, parseFailure(response))
        return response
    }

    private class StatusFailure(
        val status: HttpStatusCode,
        val failure: WebResult.Failure,
    ) : Exception(failure.message)

    private suspend inline fun <reified T> authorized(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): WebResult<T> = authorizedRequest { raw(method, path, body, token = accessToken) }

    private suspend inline fun <reified T> authorizedRequest(crossinline request: suspend () -> HttpResponse): WebResult<T> {
        var response = runCatching { request() }.getOrElse { return WebResult.Failure("NETWORK", it.message ?: "The server is unreachable.") }
        if (response.status == HttpStatusCode.Unauthorized && refresh()) {
            response = runCatching { request() }.getOrElse { return WebResult.Failure("NETWORK", it.message ?: "The server is unreachable.") }
        }
        return decodeResponse(response)
    }

    private suspend fun rawPost(
        path: String,
        body: String,
    ): HttpResponse = raw(HttpMethod.Post, path, body, ContentType.Application.Json.toString())

    private suspend fun raw(
        method: HttpMethod,
        path: String,
        body: String? = null,
        contentTypeValue: String? = null,
        token: String? = null,
    ): HttpResponse =
        when (method) {
            HttpMethod.Get -> client.get("$base$path") { requestHeaders(token) }
            HttpMethod.Post ->
                client.post("$base$path") {
                    requestHeaders(token, contentTypeValue)
                    body?.let { setBody(it) }
                }
            HttpMethod.Put ->
                client.put("$base$path") {
                    requestHeaders(token, contentTypeValue)
                    body?.let { setBody(it) }
                }
            HttpMethod.Patch ->
                client.patch("$base$path") {
                    requestHeaders(token, contentTypeValue)
                    body?.let { setBody(it) }
                }
            HttpMethod.Delete -> client.delete("$base$path") { requestHeaders(token) }
            else -> error("Unsupported method $method")
        }

    private fun io.ktor.client.request.HttpRequestBuilder.requestHeaders(
        token: String?,
        contentTypeValue: String? = null,
    ) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentTypeValue?.let { header(HttpHeaders.ContentType, it) }
    }

    private suspend inline fun <reified T> decodeResponse(response: HttpResponse): WebResult<T> {
        if (!response.status.isSuccess()) return parseFailure(response)
        val value = decode<T>(response) ?: return invalidResponse(response)
        return WebResult.Success(value)
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T? =
        try {
            response.body<T>()
        } catch (_: Exception) {
            null
        }

    private suspend fun parseFailure(response: HttpResponse): WebResult.Failure {
        val text = runCatching { response.bodyAsText() }.getOrNull()
        val error =
            text?.let {
                runCatching { json.decodeFromString(ApiErrorView.serializer(), it) }.getOrNull()
            }
        return WebResult.Failure(error?.code ?: "HTTP_${response.status.value}", error?.message ?: response.status.description, response.status.value)
    }

    private fun invalidResponse(response: HttpResponse): WebResult.Failure = WebResult.Failure("INVALID_RESPONSE", "The server returned an invalid response.", response.status.value)

    @Serializable private data class CommitImport(
        val applyUpdates: Boolean = false,
    )
}

fun WebResult.Failure.isAuthFailure(): Boolean = status == HttpStatusCode.Unauthorized.value || status == HttpStatusCode.Forbidden.value

private fun randomToken(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(24) { append(chars[(it * 37 + 13) % chars.length]) } }
}
