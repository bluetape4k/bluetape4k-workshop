package io.bluetape4k.workshop.storage

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * 모든 [StorageService] 구현의 기본 테스트 클래스입니다.
 *
 * 하위 클래스는 `@ActiveProfiles`로 적절한 Spring profile을 활성화합니다.
 */
abstract class AbstractStorageServiceTest(
    protected val storageService: StorageService,
) {

    companion object : KLogging() {
        val TEST_CONTENT = "Hello, Storage Abstraction!".toUtf8Bytes()
        const val TEST_CONTENT_TYPE = "text/plain"
    }

    @Test
    fun `upload returns a non-blank URL`() = runSuspendIO {
        val key = testKey()
        val url = storageService.upload(key, TEST_CONTENT, TEST_CONTENT_TYPE)
        url.shouldNotBeNull().shouldNotBeBlank()
        storageService.delete(key)
    }

    @Test
    fun `download returns the same bytes that were uploaded`() = runSuspendIO {
        val key = testKey()
        storageService.upload(key, TEST_CONTENT, TEST_CONTENT_TYPE)
        val downloaded = storageService.download(key)
        downloaded shouldBeEqualTo TEST_CONTENT
        storageService.delete(key)
    }

    @Test
    fun `getUrl returns a non-blank URL for an existing key`() = runSuspendIO {
        val key = testKey()
        storageService.upload(key, TEST_CONTENT, TEST_CONTENT_TYPE)
        val url = storageService.getUrl(key)
        url.shouldNotBeBlank()
        storageService.delete(key)
    }

    @Test
    fun `delete removes the object without error`() = runSuspendIO {
        val key = testKey()
        storageService.upload(key, TEST_CONTENT, TEST_CONTENT_TYPE)
        storageService.delete(key)
    }

    @Test
    fun `delete is idempotent for missing key`() = runSuspendIO {
        storageService.delete("non-existent/key.txt")
    }

    @Test
    fun `blank key is rejected`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            storageService.upload(" ", TEST_CONTENT, TEST_CONTENT_TYPE)
        }
    }

    @Test
    fun `path traversal key is rejected`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            storageService.upload("../escape.txt", TEST_CONTENT, TEST_CONTENT_TYPE)
        }
    }

    @Test
    fun `absolute key is rejected`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            storageService.upload(Path.of("/tmp/escape.txt").toString(), TEST_CONTENT, TEST_CONTENT_TYPE)
        }
    }

    protected fun testKey(): String =
        "test/${Base58.randomString(8)}.txt"
}
