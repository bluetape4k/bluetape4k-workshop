package io.bluetape4k.workshop.storage

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("s3-presigned")
class S3PresignedStorageServiceTest : AbstractStorageServiceTest() {

    companion object : KLogging()

    @Test
    fun `getUrl returns a presigned URL containing X-Amz-Expires`() = runSuspendIO {
        storageService.upload(TEST_KEY, TEST_CONTENT, TEST_CONTENT_TYPE)
        val url = storageService.getUrl(TEST_KEY)
        url.lowercase() shouldContain "x-amz-expires"
    }

    @Test
    fun `presigned URL reflects configured expiry of 15 minutes (900 seconds)`() = runSuspendIO {
        storageService.upload(TEST_KEY, TEST_CONTENT, TEST_CONTENT_TYPE)
        val url = storageService.getUrl(TEST_KEY)
        url.lowercase() shouldContain "x-amz-expires=900"
    }
}
