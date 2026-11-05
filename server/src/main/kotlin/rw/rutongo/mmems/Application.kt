package rw.rutongo.mmems

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.koin.dsl.module as koinModule
import org.koin.ktor.plugin.Koin
import rw.rutongo.mmems.platform.Clock
import rw.rutongo.mmems.platform.BlobStore
import rw.rutongo.mmems.platform.PrivateFileBlobStore
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.identityRoutes
import rw.rutongo.mmems.modules.equipment.EquipmentService
import rw.rutongo.mmems.modules.equipment.equipmentRoutes
import rw.rutongo.mmems.modules.importer.ImportService
import rw.rutongo.mmems.modules.importer.importRoutes
import rw.rutongo.mmems.modules.status.StatusService
import rw.rutongo.mmems.modules.status.statusRoutes
import java.time.Instant
import java.nio.file.Path

@Serializable
data class ApiError(val code: String, val message: String, val requestId: String? = null)

fun Application.module() {
    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/mmems"
        username = System.getenv("DATABASE_USER") ?: "mmems"
        password = System.getenv("DATABASE_PASSWORD") ?: "local-dev-only"
        maximumPoolSize = 10
    })
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
    environment.monitor.subscribe(ApplicationStopped) { dataSource.close() }
    val signer = TokenSigner.fromEnvironment()
    val identity = IdentityService(dataSource, signer)
    val equipment = EquipmentService(dataSource)
    val importer = ImportService(dataSource)
    val statuses = StatusService(dataSource)
    install(Koin) { modules(koinModule {
        single<Clock> { Clock { Instant.now() } }
        single<BlobStore> { PrivateFileBlobStore(Path.of(System.getenv("BLOB_STORAGE_DIR") ?: "data/blobs")) }
        single { identity }; single { signer }; single { equipment }; single { importer }; single { statuses }
    }) }
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is BadRequestException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("BAD_REQUEST", "The request is invalid."))
            } else {
                call.application.environment.log.error("Unhandled request failure", cause)
                call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL_ERROR", "An unexpected error occurred."))
            }
        }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        identityRoutes(identity, signer)
        equipmentRoutes(equipment, identity, signer)
        importRoutes(importer, identity, signer)
        statusRoutes(statuses, identity, signer)
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "bootstrap-admin") {
        val config = HikariConfig().apply {
            jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/mmems"
            username = System.getenv("DATABASE_USER") ?: "mmems"
            password = System.getenv("DATABASE_PASSWORD") ?: "local-dev-only"
            maximumPoolSize = 2
        }
        HikariDataSource(config).use { dataSource ->
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
            val username = System.getenv("ADMIN_USERNAME") ?: error("ADMIN_USERNAME is required")
            val fullName = System.getenv("ADMIN_FULL_NAME") ?: error("ADMIN_FULL_NAME is required")
            val employeeId = System.getenv("ADMIN_EMPLOYEE_ID") ?: error("ADMIN_EMPLOYEE_ID is required")
            val password = System.getenv("ADMIN_INITIAL_PASSWORD") ?: error("ADMIN_INITIAL_PASSWORD is required")
            IdentityService(dataSource, TokenSigner.fromEnvironment()).bootstrapAdmin(username, fullName, employeeId, password.toCharArray())
        }
        return
    }
    io.ktor.server.netty.EngineMain.main(args)
}
