package io.bluetape4k.workshop.storage

import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("s3-presigned")
class S3PresignedStorageServiceTest : AbstractStorageServiceTest() {

    companion object : KLogging()

    @Test
    fun `getUrl returns a presigned URL containing X-Amz-Expires`() = runTest {
        storageService.upload(TEST_KEY, TEST_CONTENT, TEST_CONTENT_TYPE)
        val url = storageService.getUrl(TEST_KEY)
        assertTrue(
            url.contains("X-Amz-Expires", ignoreCase = true),
            "Presigned URL must contain X-Amz-Expires, got: $url"
        )
    }

    @Test
    fun `presigned URL reflects configured expiry of 15 minutes (900 seconds)`() = runTest {
        storageService.upload(TEST_KEY, TEST_CONTENT, TEST_CONTENT_TYPE)
        val url = storageService.getUrl(TEST_KEY)
        assertTrue(
            url.contains("X-Amz-Expires=900", ignoreCase = true),
            "Presigned URL must contain X-Amz-Expires=900, got: $url"
        )
    }
}
