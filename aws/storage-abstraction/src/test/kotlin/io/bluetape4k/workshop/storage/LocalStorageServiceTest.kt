package io.bluetape4k.workshop.storage

import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("local")
class LocalStorageServiceTest @Autowired constructor(
    storageService: StorageService,
) : AbstractStorageServiceTest(storageService) {
    companion object : KLogging()
}
