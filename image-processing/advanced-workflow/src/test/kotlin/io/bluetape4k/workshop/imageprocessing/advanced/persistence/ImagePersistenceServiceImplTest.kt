package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobFailureReason
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobIdentity
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Integration tests for [ImagePersistenceService] — covers all saga steps and query paths.
 *
 * ## Isolation strategy
 * Each test uses a unique checksum so tests do not share rows.
 * Transaction rollback is NOT used here because [ImagePersistenceServiceImpl] commits
 * inside its own PROPAGATION_REQUIRES_NEW transactions.
 */
class ImagePersistenceServiceImplTest : AbstractImagePersistenceTest() {

    @Autowired
    private lateinit var service: ImagePersistenceService

    // -------------------------------------------------------------------------
    // T1 — recordJobStart variants
    // -------------------------------------------------------------------------

    @Test
    fun `recordJobStart - new asset creates NewAsset result`() {
        val checksum = "sha256-${UUID.randomUUID()}"
        val metadata = buildMetadata(checksum = checksum)

        val result = service.recordJobStart(metadata)

        log.info { "recordJobStart result: $result" }
        (result is JobStartResult.NewAsset).shouldBeTrue()
        (result.assetId > 0L).shouldBeTrue()
        (result.jobId > 0L).shouldBeTrue()
        result.externalId.shouldNotBeBlank()
    }

    @Test
    fun `recordJobStart - duplicate checksum READY returns AlreadyReady`() {
        val checksum = "sha256-${UUID.randomUUID()}"
        val metadata = buildMetadata(checksum = checksum)

        val firstResult = service.recordJobStart(metadata)
        (firstResult is JobStartResult.NewAsset).shouldBeTrue()
        val firstIdentity = JobIdentity(jobId = firstResult.jobId, assetId = firstResult.assetId)

        service.recordJobSuccess(firstIdentity, buildObjects(firstResult.assetId))

        val secondResult = service.recordJobStart(metadata)
        log.debug { "second recordJobStart result: $secondResult" }
        (secondResult is JobStartResult.AlreadyReady).shouldBeTrue()
        secondResult.jobId shouldBeEqualTo -1L
        secondResult.externalId shouldBeEqualTo firstResult.externalId
    }

    @Test
    fun `recordJobStart - duplicate checksum PROCESSING returns ConcurrentProcessing`() {
        val checksum = "sha256-${UUID.randomUUID()}"
        val metadata = buildMetadata(checksum = checksum)

        val firstResult = service.recordJobStart(metadata)
        (firstResult is JobStartResult.NewAsset).shouldBeTrue()

        val secondResult = service.recordJobStart(metadata)
        log.debug { "second recordJobStart result: $secondResult" }
        (secondResult is JobStartResult.ConcurrentProcessing).shouldBeTrue()
        secondResult.assetId shouldBeEqualTo firstResult.assetId
        secondResult.externalId shouldBeEqualTo firstResult.externalId
        (secondResult.jobId > 0L).shouldBeTrue()
    }

    @Test
    fun `recordJobStart - duplicate checksum FAILED returns RecoveredFromFailed`() {
        val checksum = "sha256-${UUID.randomUUID()}"
        val metadata = buildMetadata(checksum = checksum)

        val firstResult = service.recordJobStart(metadata)
        (firstResult is JobStartResult.NewAsset).shouldBeTrue()
        val firstIdentity = JobIdentity(jobId = firstResult.jobId, assetId = firstResult.assetId)

        service.recordJobFailure(
            firstIdentity,
            JobFailureReason(errorCode = "TEST_ERROR", errorMessage = "Test failure"),
            durationMs = 100L,
        )

        val recoveredResult = service.recordJobStart(metadata)
        log.debug { "recovered recordJobStart result: $recoveredResult" }
        (recoveredResult is JobStartResult.RecoveredFromFailed).shouldBeTrue()
        recoveredResult.assetId shouldBeEqualTo firstResult.assetId
        recoveredResult.externalId shouldBeEqualTo firstResult.externalId
        (recoveredResult.jobId > 0L).shouldBeTrue()
    }

    // -------------------------------------------------------------------------
    // T2 — recordJobSuccess
    // -------------------------------------------------------------------------

    @Test
    fun `recordJobSuccess - updates asset READY and persists image objects`() {
        val checksum = "sha256-${UUID.randomUUID()}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        (startResult is JobStartResult.NewAsset).shouldBeTrue()

        val identity = JobIdentity(jobId = startResult.jobId, assetId = startResult.assetId)
        service.recordJobSuccess(identity, buildObjects(startResult.assetId))

        val detail = service.findAssetByExternalId(startResult.externalId)
        val nonNullDetail = detail.shouldNotBeNull()
        nonNullDetail.status shouldBeEqualTo ImageAssetStatus.READY
        nonNullDetail.original.shouldNotBeNull()
        nonNullDetail.variants shouldHaveSize 1
        nonNullDetail.variants.first().variantName shouldBeEqualTo "thumbnail"
    }

    // -------------------------------------------------------------------------
    // T3 — recordJobFailure
    // -------------------------------------------------------------------------

    @Test
    fun `recordJobFailure - updates asset FAILED`() {
        val checksum = "sha256-${UUID.randomUUID()}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        (startResult is JobStartResult.NewAsset).shouldBeTrue()

        val identity = JobIdentity(jobId = startResult.jobId, assetId = startResult.assetId)
        service.recordJobFailure(
            identity,
            JobFailureReason(errorCode = "VIPS_ERROR", errorMessage = "libvips failed to decode image"),
            durationMs = 250L,
        )

        val detail = service.findAssetByExternalId(startResult.externalId)
        val nonNullDetail = detail.shouldNotBeNull()
        nonNullDetail.status shouldBeEqualTo ImageAssetStatus.FAILED
    }

    // -------------------------------------------------------------------------
    // appendEvent
    // -------------------------------------------------------------------------

    @Test
    fun `appendEvent - stores event row`() {
        val checksum = "sha256-${UUID.randomUUID()}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        (startResult is JobStartResult.NewAsset).shouldBeTrue()

        service.appendEvent(
            jobId = startResult.jobId,
            step = ImageProcessingStep.VALIDATION,
            status = ImageProcessingEventStatus.COMPLETED,
            message = "Validation passed",
        )

        val history = service.findAssetHistory(startResult.externalId)
        val nonNullHistory = history.shouldNotBeNull()
        nonNullHistory.jobs shouldHaveSize 1
        nonNullHistory.jobs.first().events shouldHaveSize 1
    }

    // -------------------------------------------------------------------------
    // findAssetByExternalId — null path
    // -------------------------------------------------------------------------

    @Test
    fun `findAssetByExternalId - returns null for unknown externalId`() {
        service.findAssetByExternalId(UUID.randomUUID().toString()).shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // findAssetHistory — null path
    // -------------------------------------------------------------------------

    @Test
    fun `findAssetHistory - returns null for unknown externalId`() {
        service.findAssetHistory(UUID.randomUUID().toString()).shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // findAssetHistory — full job + event history
    // -------------------------------------------------------------------------

    @Test
    fun `findAssetHistory - returns full job and event history`() {
        val checksum = "sha256-${UUID.randomUUID()}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        (startResult is JobStartResult.NewAsset).shouldBeTrue()
        val identity = JobIdentity(jobId = startResult.jobId, assetId = startResult.assetId)

        service.appendEvent(
            jobId = startResult.jobId,
            step = ImageProcessingStep.VALIDATION,
            status = ImageProcessingEventStatus.COMPLETED,
            message = "Input validated",
        )
        service.appendEvent(
            jobId = startResult.jobId,
            step = ImageProcessingStep.VIPS_PROCESSING,
            status = ImageProcessingEventStatus.COMPLETED,
            message = "Image resized",
        )
        service.recordJobSuccess(identity, buildObjects(startResult.assetId))

        val history = service.findAssetHistory(startResult.externalId)
        val nonNullHistory = history.shouldNotBeNull()
        nonNullHistory.imageId shouldBeEqualTo startResult.externalId
        nonNullHistory.jobs shouldHaveSize 1
        nonNullHistory.jobs.first().events shouldHaveSize 2
    }
}
