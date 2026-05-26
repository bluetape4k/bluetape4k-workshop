package io.bluetape4k.workshop.storage

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired

/**
 * Base test class for all [StorageService] implementations.
 *
 * Subclasses activate the appropriate Spring profile via `@ActiveProfiles`.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
abstract class AbstractStorageServiceTest {

    companion object : KLogging() {
        const val TEST_KEY = "test/hello.txt"
        val TEST_CONTENT = "Hello, Storage Abstraction!".toUtf8Bytes()
        const val TEST_CONTENT_TYPE = "text/plain"
    }

    @Autowired
    lateinit var storageService: StorageService

    @Test
    @Order(1)
    fun `upload returns a non-blank URL`() = runSuspendIO {
        val url = storageService.upload(TEST_KEY, TEST_CONTENT, TEST_CONTENT_TYPE)
        url.shouldNotBeNull().shouldNotBeBlank()
    }

    @Test
    @Order(2)
    fun `download returns the same bytes that were uploaded`() = runSuspendIO {
        storageService.upload(TEST_KEY, TEST_CONTENT, TEST_CONTENT_TYPE)
        val downloaded = storageService.download(TEST_KEY)
        downloaded shouldBeEqualTo TEST_CONTENT
    }

    @Test
    @Order(3)
    fun `getUrl returns a non-blank URL for an existing key`() = runSuspendIO {
        storageService.upload(TEST_KEY, TEST_CONTENT, TEST_CONTENT_TYPE)
        val url = storageService.getUrl(TEST_KEY)
        url.shouldNotBeBlank()
    }

    @Test
    @Order(4)
    fun `delete removes the object without error`() = runSuspendIO {
        storageService.upload(TEST_KEY, TEST_CONTENT, TEST_CONTENT_TYPE)
        // Should not throw
        storageService.delete(TEST_KEY)
    }

    @Test
    @Order(5)
    fun `delete is idempotent for missing key`() = runSuspendIO {
        // Delete a key that was never uploaded — must not throw
        storageService.delete("non-existent/key.txt")
    }
}
