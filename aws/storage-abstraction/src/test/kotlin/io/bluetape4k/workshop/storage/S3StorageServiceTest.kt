package io.bluetape4k.workshop.storage

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("s3")
class S3StorageServiceTest @Autowired constructor(
    storageService: StorageService,
) : AbstractStorageServiceTest(storageService) {
    companion object : KLogging()

    @Test
    fun `upload returns endpoint neutral s3 object uri`() = runSuspendIO {
        val key = testKey()

        val url = storageService.upload(key, TEST_CONTENT, TEST_CONTENT_TYPE)

        url shouldContain "s3://"
        url shouldContain key
        storageService.delete(key)
    }
}
