@file:Suppress("TooGenericExceptionCaught")

package rw.rutongo.mmems.modules.photo

import kotlinx.serialization.Serializable
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.platform.BlobStore
import rw.rutongo.mmems.shared.enums.Role
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

@Serializable data class PhotoView(
    val id: String,
    val mime: String,
    val sizeBytes: Int,
    val sha256: String,
    val siteId: String,
    val targetType: String,
    val targetId: String,
    val kind: String,
    val uploadedAt: String,
)

data class PhotoContent(
    val mime: String,
    val sizeBytes: Int,
    val bytes: ByteArray,
)

class PhotoService(
    private val dataSource: DataSource,
    private val blobStore: BlobStore,
) {
    fun upload(
        actor: Principal,
        siteId: UUID,
        targetType: String,
        targetId: UUID,
        kind: String,
        declaredContentType: String?,
        bytes: ByteArray,
        requestedId: UUID? = null,
    ): PhotoView {
        require(targetType in TARGET_TYPES) { "The photo target type is invalid." }
        require(kind in KINDS) { "The photo kind is invalid." }
        require(targetType != "BREAKDOWN" || RolePermissions.allows(actor.role, "BREAKDOWN_MANAGE")) { "FORBIDDEN" }
        require(bytes.isNotEmpty()) { "The photo is empty." }
        require(bytes.size <= MAX_BYTES) { "The photo exceeds 10 MiB." }
        val image = ImageType.detect(bytes) ?: throw IllegalArgumentException("Only JPEG and PNG images are accepted.")
        if (!declaredContentType.isNullOrBlank() && declaredContentType != "application/octet-stream" && declaredContentType != image.mime) {
            throw IllegalArgumentException("The declared image type does not match the file content.")
        }
        require(hasSiteAccess(actor, siteId)) { "SITE_NOT_AUTHORIZED" }
        val id = requestedId ?: UUID.randomUUID()
        val key = blobStore.put(ByteArrayInputStream(bytes), image.suffix)
        try {
            val uploadedAt =
                dataSource.connection.use { c ->
                    validateTarget(c, siteId, targetType, targetId)
                    c
                        .prepareStatement(
                            "insert into photo(id,storage_key,sha256,mime,size_bytes,uploader_id,site_id,target_type,target_id,kind) values (?,?,?,?,?,?,?,?,?,?) returning uploaded_at",
                        ).use { ps ->
                            ps.setObject(1, id)
                            ps.setString(2, key)
                            ps.setString(3, sha256(bytes))
                            ps.setString(4, image.mime)
                            ps.setInt(5, bytes.size)
                            ps.setObject(6, actor.id)
                            ps.setObject(7, siteId)
                            ps.setString(8, targetType)
                            ps.setObject(9, targetId)
                            ps.setString(10, kind)
                            ps.executeQuery().use {
                                it.next()
                                it.getTimestamp(1).toInstant()
                            }
                        }
                }
            return PhotoView(id.toString(), image.mime, bytes.size, sha256(bytes), siteId.toString(), targetType, targetId.toString(), kind, uploadedAt.toString())
        } catch (e: Exception) {
            blobStore.delete(key)
            throw e
        }
    }

    fun open(
        actor: Principal,
        id: UUID,
    ): PhotoContent {
        val metadata =
            dataSource.connection.use { c ->
                c.prepareStatement("select storage_key,mime,size_bytes,site_id from photo where id=?").use { ps ->
                    ps.setObject(1, id)
                    ps.executeQuery().use {
                        if (!it.next()) throw AuthFailure("PHOTO_NOT_FOUND")
                        PhotoMetadata(it.getString(1), it.getString(2), it.getInt(3), it.getObject(4, UUID::class.java))
                    }
                }
            }
        if (!hasSiteAccess(actor, metadata.siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
        return PhotoContent(metadata.mime, metadata.sizeBytes, blobStore.open(metadata.storageKey).use { it.readBytes() })
    }

    private fun hasSiteAccess(
        actor: Principal,
        siteId: UUID,
    ): Boolean =
        dataSource.connection.use { c ->
            hasSiteAccess(c, actor, siteId)
        }

    private fun hasSiteAccess(
        c: Connection,
        actor: Principal,
        siteId: UUID,
    ) = c.prepareStatement("select exists(select 1 from site where id=? and state<>'RETIRED') and (exists(select 1 from user_site_access where user_id=? and site_id=?) or (? and not exists(select 1 from user_site_access where user_id=?)))").use {
        it.setObject(1, siteId)
        it.setObject(2, actor.id)
        it.setObject(3, siteId)
        it.setBoolean(4, actor.role in broadRoles)
        it.setObject(5, actor.id)
        it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
    }

    private fun validateTarget(
        c: Connection,
        siteId: UUID,
        targetType: String,
        targetId: UUID,
    ) {
        if (targetType != "BREAKDOWN") return
        val targetSite =
            c.prepareStatement("select site_id from breakdown where id=?").use { ps ->
                ps.setObject(1, targetId)
                ps.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
            }
        requireNotNull(targetSite) { "BREAKDOWN_NOT_FOUND" }
        require(targetSite == siteId) { "SITE_NOT_AUTHORIZED" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class PhotoMetadata(
        val storageKey: String,
        val mime: String,
        val sizeBytes: Int,
        val siteId: UUID,
    )

    private enum class ImageType(
        val mime: String,
        val suffix: String,
    ) {
        JPEG("image/jpeg", ".jpg"),
        PNG("image/png", ".png"),
        ;

        companion object {
            val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

            fun detect(bytes: ByteArray): ImageType? =
                when {
                    bytes.size >= pngMagic.size && pngMagic.indices.all { bytes[it] == pngMagic[it] } -> PNG
                    bytes.size >= jpegMagic.size && jpegMagic.indices.all { bytes[it] == jpegMagic[it] } -> JPEG
                    else -> null
                }
        }
    }

    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        private val TARGET_TYPES = setOf("EQUIPMENT", "INSPECTION_ANSWER", "DEFECT", "BREAKDOWN", "MAINTENANCE", "STATUS_EVENT")
        private val KINDS = setOf("EVIDENCE", "BEFORE", "AFTER")
        private val broadRoles = setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)
    }
}
