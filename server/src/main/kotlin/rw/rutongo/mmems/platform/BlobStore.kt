package rw.rutongo.mmems.platform

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

interface BlobStore {
    fun put(
        content: InputStream,
        suffix: String,
    ): String

    fun open(key: String): InputStream

    fun delete(key: String)
}

class PrivateFileBlobStore(
    root: Path,
) : BlobStore {
    private val directory = root.toAbsolutePath().normalize()

    init {
        Files.createDirectories(directory)
    }

    override fun put(
        content: InputStream,
        suffix: String,
    ): String {
        require(suffix.matches(Regex("\\.[a-z0-9]{1,8}")))
        val key = "${UUID.randomUUID()}$suffix"
        val target = resolve(key)
        val temporary = Files.createTempFile(directory, ".upload-", ".tmp")
        try {
            content.use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(temporary)
            throw e
        }
        return key
    }

    override fun open(key: String): InputStream = Files.newInputStream(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    private fun resolve(key: String): Path {
        require(key.matches(Regex("[0-9a-fA-F-]{36}\\.[a-z0-9]{1,8}"))) { "Invalid blob key" }
        val path = directory.resolve(key).normalize()
        require(path.parent == directory) { "Blob key escapes the private store" }
        return path
    }
}
