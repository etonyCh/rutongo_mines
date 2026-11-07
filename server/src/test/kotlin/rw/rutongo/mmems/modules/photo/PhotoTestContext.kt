package rw.rutongo.mmems.modules.photo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.platform.PrivateFileBlobStore
import rw.rutongo.mmems.shared.enums.Role
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class PhotoTestContext(
    postgres: PostgreSQLContainer<Nothing>,
) : AutoCloseable {
    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 3
            },
        )
    private val directory: Path = Files.createTempDirectory("rutongo-photo-test")
    val service = PhotoService(dataSource, PrivateFileBlobStore(directory))

    init {
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
    }

    fun site(prefix: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into site(id,code,name,state,timezone) values (?,?,?,'ACTIVE','Africa/Kigali')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "$prefix-${id.toString().take(8)}".uppercase())
                ps.setString(3, "$prefix Site")
                ps.executeUpdate()
            }
        }
        return id
    }

    fun actor(
        role: Role,
        sites: List<UUID>,
    ): Principal {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "photo-user-$id")
                ps.setString(3, "Photo User")
                ps.setString(4, "EID-$id")
                ps.setString(5, role.name)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                sites.forEach {
                    ps.setObject(1, id)
                    ps.setObject(2, it)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        return Principal(id, "photo-user-$id", role, false)
    }

    override fun close() {
        dataSource.close()
        directory.toFile().deleteRecursively()
    }

    companion object {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00)
    }
}
