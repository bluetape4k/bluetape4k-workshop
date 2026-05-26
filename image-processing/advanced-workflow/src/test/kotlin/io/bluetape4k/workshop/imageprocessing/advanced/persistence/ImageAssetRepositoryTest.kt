package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobIdentity
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import io.bluetape4k.codec.Base58

/**
 * Integration tests for [ImageAssetRepository] low-level operations.
 *
 * Uses [ImagePersistenceService.recordJobStart] to create rows in a realistic way
 * (respecting UserContext and auditing) instead of calling the repository directly,
 * which would require an open transaction and a UserContext block set up manually.
 */
class ImageAssetRepositoryTest : AbstractImagePersistenceTest() {

    @Autowired
    private lateinit var service: ImagePersistenceService

    @Test
    fun `findByExternalId - returns null when asset does not exist`() {
        val result = service.findAssetByExternalId(Base58.randomString(12))
        result.shouldBeNull()
    }

    @Test
    fun `findByExternalId - returns asset after creation via recordJobStart`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val metadata = buildMetadata(checksum = checksum)

        val startResult = service.recordJobStart(metadata)
        (startResult is JobStartResult.NewAsset).shouldBeTrue()

        val detail = service.findAssetByExternalId(startResult.externalId)
        log.info { "Asset detail after recordJobStart: $detail" }
        val nonNullDetail = detail.shouldNotBeNull()
        nonNullDetail.imageId shouldBeEqualTo startResult.externalId
    }

    @Test
    fun `updateStatus - changes asset status to READY via recordJobSuccess`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val metadata = buildMetadata(checksum = checksum)

        val startResult = service.recordJobStart(metadata)
        (startResult is JobStartResult.NewAsset).shouldBeTrue()

        val identity = JobIdentity(
            jobId = startResult.jobId,
            assetId = startResult.assetId,
        )
        service.recordJobSuccess(identity, buildObjects(startResult.assetId))

        val detail = service.findAssetByExternalId(startResult.externalId)
        val nonNullDetail = detail.shouldNotBeNull()
        nonNullDetail.status shouldBeEqualTo ImageAssetStatus.READY
    }
}
