package rw.rutongo.mmems

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin

fun securityHeadersPlugin(
    hstsEnabled: Boolean,
    maxRequestBytes: Long = MAX_REQUEST_BYTES,
) = createApplicationPlugin(name = "SecurityHeaders") {
    onCall { call ->
        addSecurityHeaders(call, hstsEnabled)
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > maxRequestBytes) {
            throw RequestTooLargeException()
        }
    }
}

private fun addSecurityHeaders(
    call: ApplicationCall,
    hstsEnabled: Boolean,
) {
    call.response.headers.append("X-Content-Type-Options", "nosniff")
    call.response.headers.append("X-Frame-Options", "DENY")
    call.response.headers.append("Referrer-Policy", "no-referrer")
    call.response.headers.append(
        "Permissions-Policy",
        "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()",
    )
    call.response.headers.append("Cross-Origin-Opener-Policy", "same-origin")
    call.response.headers.append("X-Permitted-Cross-Domain-Policies", "none")
    call.response.headers.append("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
    if (hstsEnabled) {
        call.response.headers.append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
    }
}

internal const val MAX_REQUEST_BYTES = 25L * 1024 * 1024
