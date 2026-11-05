package rw.rutongo.mmems.modules.importer

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.TokenSigner
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class ImportIdempotencyTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    @Test
    fun `preview commit and repeated import are idempotent without writing operational status`() {
        val dataSource=HikariDataSource(HikariConfig().apply { jdbcUrl=postgres.jdbcUrl; username=postgres.username; password=postgres.password; maximumPoolSize=3 })
        val storage=Files.createTempDirectory("rutongo-import-test")
        try {
            Flyway.configure().dataSource(dataSource).load().migrate()
            val identity=IdentityService(dataSource,TokenSigner.createForTests())
            identity.bootstrapAdmin("import-admin","Import Admin","EID-IMPORT","ImportAdminPassword1".toCharArray())
            val actor=dataSource.connection.use { c -> c.prepareStatement("select id from app_user where username='import-admin'").use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getObject(1,UUID::class.java) } } }
            val principal=identity.currentPrincipal(actor)!!
            val importer=ImportService(dataSource,storage)
            val profile=importer.createProfile(actor,"Asset mapping",ImportKind.ASSET,"Assets",mapOf(
                "asset_id" to "Asset ID","equipment_code" to "Code","name" to "Equipment","type" to "Type","site" to "Location","model" to "Model"
            ))
            val workbook=workbookBytes()

            val firstPreview=importer.preview(principal,UUID.fromString(profile.id),"assets.xlsx",workbook)
            assertEquals(1,firstPreview.newCount)
            assertEquals(listOf("Forklift"),firstPreview.newEquipmentTypes)
            importer.commit(principal,UUID.fromString(firstPreview.id),applyUpdates=false)

            val repeatedPreview=importer.preview(principal,UUID.fromString(profile.id),"assets.xlsx",workbook)
            assertNotEquals(firstPreview.id,repeatedPreview.id)
            assertEquals(1,repeatedPreview.unchangedCount)
            assertEquals(0,repeatedPreview.newCount)
            importer.commit(principal,UUID.fromString(repeatedPreview.id),applyUpdates=true)

            dataSource.connection.use { c ->
                c.createStatement().use { statement ->
                    statement.executeQuery("select count(*),min(current_status) from equipment where asset_id='ASSET-001'").use { rs -> rs.next(); assertEquals(1,rs.getInt(1)); assertNull(rs.getString(2)) }
                    statement.executeQuery("select count(*) from equipment_type where name='Forklift'").use { rs -> rs.next(); assertEquals(1,rs.getInt(1)) }
                }
            }
            assertTrue(Files.list(storage).use { it.count() >= 2 })
        } finally { dataSource.close(); storage.toFile().deleteRecursively() }
    }

    private fun workbookBytes(): ByteArray {
        val workbook=XSSFWorkbook()
        workbook.use { book ->
            val sheet=book.createSheet("Assets")
            val headers=listOf("Asset ID","Code","Equipment","Type","Location","Model","Internal Tag")
            val headerRow=sheet.createRow(0)
            headers.forEachIndexed { column,value -> headerRow.createCell(column).setCellValue(value) }
            val row=sheet.createRow(1)
            listOf("ASSET-001","EQ-001","Underground Forklift","Forklift","Nyamyumba","F-100","Preserve me").forEachIndexed { column,value -> row.createCell(column).setCellValue(value) }
            return ByteArrayOutputStream().use { output -> book.write(output); output.toByteArray() }
        }
    }
}
