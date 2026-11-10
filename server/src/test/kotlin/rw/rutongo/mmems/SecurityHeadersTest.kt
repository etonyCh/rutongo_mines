package rw.rutongo.mmems

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityHeadersTest {
    @Test
    fun `adds baseline security headers and hsts when enabled`() =
        testApplication {
            application {
                install(securityHeadersPlugin(hstsEnabled = true))
                routing {
                    get("/healthz") { call.respondText("ok") }
                }
            }

            val response = client.get("/healthz")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
            assertEquals("same-origin", response.headers["Cross-Origin-Opener-Policy"])
            assertEquals(
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
                response.headers["Content-Security-Policy"],
            )
            val hsts = response.headers["Strict-Transport-Security"].orEmpty()
            assertTrue(hsts.startsWith("max-age=31536000"))
        }

    @Test
    fun `omits hsts when disabled`() =
        testApplication {
            application {
                install(securityHeadersPlugin(hstsEnabled = false))
                routing {
                    get("/healthz") { call.respondText("ok") }
                }
            }

            val response = client.get("/healthz")

            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(response.headers["Strict-Transport-Security"])
        }

    @Test
    fun `rejects oversized request bodies`() =
        testApplication {
            application {
                install(securityHeadersPlugin(hstsEnabled = false, maxRequestBytes = 16))
                install(StatusPages) {
                    exception<RequestTooLargeException> { call, _ ->
                        call.respond(HttpStatusCode.PayloadTooLarge)
                    }
                }
                routing {
                    post("/healthz") { call.respondText("ok") }
                }
            }

            val response = client.post("/healthz") { setBody("0123456789abcdefg") }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        }
}
