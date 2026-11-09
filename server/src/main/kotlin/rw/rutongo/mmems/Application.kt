package rw.rutongo.mmems

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.koin.ktor.plugin.Koin
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.identityRoutes
import rw.rutongo.mmems.modules.breakdown.BreakdownService
import rw.rutongo.mmems.modules.breakdown.breakdownRoutes
import rw.rutongo.mmems.modules.dashboard.DashboardService
import rw.rutongo.mmems.modules.dashboard.dashboardRoutes
import rw.rutongo.mmems.modules.equipment.EquipmentService
import rw.rutongo.mmems.modules.equipment.equipmentRoutes
import rw.rutongo.mmems.modules.exporter.ExportService
import rw.rutongo.mmems.modules.exporter.exportRoutes
import rw.rutongo.mmems.modules.importer.ImportService
import rw.rutongo.mmems.modules.importer.importRoutes
import rw.rutongo.mmems.modules.inspection.GenerateExpectationsJob
import rw.rutongo.mmems.modules.inspection.InspectionScheduler
import rw.rutongo.mmems.modules.inspection.InspectionService
import rw.rutongo.mmems.modules.inspection.MarkMissingJob
import rw.rutongo.mmems.modules.inspection.inspectionRoutes
import rw.rutongo.mmems.modules.maintenance.MaintenanceService
import rw.rutongo.mmems.modules.maintenance.maintenanceRoutes
import rw.rutongo.mmems.modules.notification.FcmPushNotifier
import rw.rutongo.mmems.modules.notification.NotificationDispatcher
import rw.rutongo.mmems.modules.notification.NotificationScheduler
import rw.rutongo.mmems.modules.notification.NotificationService
import rw.rutongo.mmems.modules.notification.notificationRoutes
import rw.rutongo.mmems.modules.operations.OperationsScheduler
import rw.rutongo.mmems.modules.parts.PartsService
import rw.rutongo.mmems.modules.parts.partsRoutes
import rw.rutongo.mmems.modules.personnel.PersonnelService
import rw.rutongo.mmems.modules.personnel.personnelRoutes
import rw.rutongo.mmems.modules.photo.PhotoService
import rw.rutongo.mmems.modules.photo.photoRoutes
import rw.rutongo.mmems.modules.report.DailyReportPdfRenderer
import rw.rutongo.mmems.modules.report.ReportService
import rw.rutongo.mmems.modules.report.ReportSnapshotBuilder
import rw.rutongo.mmems.modules.report.reportRoutes
import rw.rutongo.mmems.modules.status.StatusService
import rw.rutongo.mmems.modules.status.statusRoutes
import rw.rutongo.mmems.modules.workinghours.WorkingHoursService
import rw.rutongo.mmems.modules.workinghours.workingHoursRoutes
import rw.rutongo.mmems.platform.BlobStore
import rw.rutongo.mmems.platform.Clock
import rw.rutongo.mmems.platform.PrivateFileBlobStore
import java.nio.file.Path
import java.time.Instant
import org.koin.dsl.module as koinModule

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val requestId: String? = null,
)

@Suppress("LongMethod")
fun Application.module() {
    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/mmems"
                username = System.getenv("DATABASE_USER") ?: "mmems"
                password = System.getenv("DATABASE_PASSWORD") ?: "local-dev-only"
                maximumPoolSize = 10
            },
        )
    Flyway
        .configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    val signer = TokenSigner.fromEnvironment()
    val identity = IdentityService(dataSource, signer)
    val equipment = EquipmentService(dataSource)
    val importer = ImportService(dataSource)
    val statuses = StatusService(dataSource)
    val inspections = InspectionService(dataSource)
    val breakdowns = BreakdownService(dataSource)
    val maintenance = MaintenanceService(dataSource)
    val workingHours = WorkingHoursService(dataSource)
    val parts = PartsService(dataSource)
    val personnel = PersonnelService(dataSource)
    val blobStore = PrivateFileBlobStore(Path.of(System.getenv("BLOB_STORAGE_DIR") ?: "data/blobs"))
    val photos = PhotoService(dataSource, blobStore)
    val reports = ReportService(dataSource, blobStore, ReportSnapshotBuilder(dataSource), DailyReportPdfRenderer())
    val dashboards = DashboardService(dataSource)
    val exports = ExportService(dataSource)
    val notifications = NotificationService(dataSource)
    val notificationDispatcher = NotificationDispatcher(dataSource, FcmPushNotifier())
    val notificationScheduler = NotificationScheduler(dataSource, notificationDispatcher)
    val operationsScheduler = OperationsScheduler(dataSource)
    val inspectionScheduler = InspectionScheduler(dataSource, GenerateExpectationsJob(dataSource), MarkMissingJob(dataSource))
    environment.monitor.subscribe(ApplicationStarted) {
        inspectionScheduler.start()
        notificationScheduler.start()
        operationsScheduler.start()
    }
    environment.monitor.subscribe(ApplicationStopped) {
        inspectionScheduler.stop()
        notificationScheduler.stop()
        operationsScheduler.stop()
        dataSource.close()
    }
    install(Koin) {
        modules(
            koinModule {
                single<Clock> { Clock { Instant.now() } }
                single<BlobStore> { blobStore }
                single { identity }
                single { signer }
                single { equipment }
                single { importer }
                single { statuses }
                single { inspections }
                single { photos }
                single { dashboards }
                single { exports }
                single { notifications }
            },
        )
    }
    install(ContentNegotiation) { json() }
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.IfMatch)
        allowHeader("Last-Event-ID")
        allowHeader("Idempotency-Key")
        allowHeader("X-CSRF-Token")
        allowHeader("X-Requested-With")
        allowCredentials = true
        val origins = (System.getenv("WEB_ORIGINS") ?: "http://localhost:8081,http://127.0.0.1:8081").split(',').map(String::trim).filter(String::isNotBlank)
        allowOrigins { origin -> origin in origins }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is BadRequestException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("BAD_REQUEST", "The request is invalid."))
            } else {
                call.application.environment.log
                    .error("Unhandled request failure", cause)
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
        inspectionRoutes(inspections, identity, signer)
        breakdownRoutes(breakdowns, identity, signer)
        maintenanceRoutes(maintenance, identity, signer)
        workingHoursRoutes(workingHours, identity, signer)
        partsRoutes(parts, identity, signer)
        personnelRoutes(personnel, identity, signer)
        photoRoutes(photos, identity, signer)
        reportRoutes(reports, identity, signer)
        dashboardRoutes(dashboards, identity, signer)
        notificationRoutes(notifications, identity, signer)
        exportRoutes(exports, identity, signer)
        eventRoutes(statuses, notifications, identity, signer)
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "bootstrap-admin") {
        val config =
            HikariConfig().apply {
                jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/mmems"
                username = System.getenv("DATABASE_USER") ?: "mmems"
                password = System.getenv("DATABASE_PASSWORD") ?: "local-dev-only"
                maximumPoolSize = 2
            }
        HikariDataSource(config).use { dataSource ->
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            val username = System.getenv("ADMIN_USERNAME") ?: error("ADMIN_USERNAME is required")
            val fullName = System.getenv("ADMIN_FULL_NAME") ?: error("ADMIN_FULL_NAME is required")
            val employeeId = System.getenv("ADMIN_EMPLOYEE_ID") ?: error("ADMIN_EMPLOYEE_ID is required")
            val password = System.getenv("ADMIN_INITIAL_PASSWORD") ?: error("ADMIN_INITIAL_PASSWORD is required")
            IdentityService(dataSource, TokenSigner.fromEnvironment()).bootstrapAdmin(username, fullName, employeeId, password.toCharArray())
        }
        return
    }
    io.ktor.server.netty.EngineMain
        .main(args)
}
