package io.bluetape4k.workshop.storage

import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("s3")
class S3StorageServiceTest : AbstractStorageServiceTest() {
    companion object : KLogging()
}
