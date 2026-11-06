package rw.rutongo.mmems.platform

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlobStoreTest {
    @Test
    fun `private file store generates opaque keys and rejects path traversal`() {
        val directory = Files.createTempDirectory("rutongo-blobs")
        try {
            val store = PrivateFileBlobStore(directory)
            val key = store.put(ByteArrayInputStream("evidence".toByteArray()), ".png")
            assertEquals("evidence", store.open(key).use { it.readBytes().decodeToString() })
            assertFailsWith<IllegalArgumentException> { store.open("../private.png") }
            store.delete(key)
            assertFailsWith<java.nio.file.NoSuchFileException> { store.open(key) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
