package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobFailureReason
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobIdentity
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageJobStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import io.bluetape4k.codec.Base58

/**
 * Integration tests for the processing-job history path exposed through [ImagePersistenceService].
 *
 * Exercises job creation, success/failure marking, and the [ImagePersistenceService.findAssetHistory]
 * query that drives `GET /images/{imageId}/history`.
 *
 * ## Isolation strategy
 * Each test uses a unique checksum — `PROPAGATION_REQUIRES_NEW` commits inside the service
 * defeat `@Rollback`, so per-test unique keys are the only safe isolation mechanism.
 */
class ImageProcessingJobRepositoryTest : AbstractImagePersistenceTest() {

    @Autowired
    private lateinit var service: ImagePersistenceService

    @Test
    fun `findAssetHistory - returns null for unknown externalId`() {
        val history = service.findAssetHistory(Base58.randomString(12))
        // Soft assert: result is null — history is absent for unknown asset
        (history == null).shouldBeTrue()
    }

    @Test
    fun `findAssetHistory - contains one RUNNING job after recordJobStart`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        (startResult is JobStartResult.NewAsset).shouldBeTrue()

        val history = service.findAssetHistory(startResult.externalId)
        val nonNullHistory = history.shouldNotBeNull()
        log.info { "History after recordJobStart: $nonNullHistory" }
        nonNullHistory.jobs shouldHaveSize 1
        nonNullHistory.jobs.first().job.status shouldBeEqualTo ImageJobStatus.RUNNING
    }

    @Test
    fun `findAssetHistory - job status is SUCCEEDED after recordJobSuccess`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        (startResult is JobStartResult.NewAsset).shouldBeTrue()

        val identity = JobIdentity(jobId = startResult.jobId, assetId = startResult.assetId)
        service.recordJobSuccess(identity, buildObjects(startResult.assetId))

        val history = service.findAssetHistory(startResult.externalId)
        val nonNullHistory = history.shouldNotBeNull()
        log.info { "History after recordJobSuccess: $nonNullHistory" }
        nonNullHistory.jobs shouldHaveSize 1
        nonNullHistory.jobs.first().job.status shouldBeEqualTo ImageJobStatus.SUCCEEDED
    }

    @Test
    fun `findAssetHistory - job status is FAILED after recordJobFailure`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        (startResult is JobStartResult.NewAsset).shouldBeTrue()

        val identity = JobIdentity(jobId = startResult.jobId, assetId = startResult.assetId)
        service.recordJobFailure(
            identity,
            JobFailureReason(errorCode = "S3_UPLOAD_ERROR", errorMessage = "S3 upload timed out"),
            durationMs = 5_000L,
        )

        val history = service.findAssetHistory(startResult.externalId)
        val nonNullHistory = history.shouldNotBeNull()
        log.info { "History after recordJobFailure: $nonNullHistory" }
        nonNullHistory.jobs shouldHaveSize 1
        val failedJob = nonNullHistory.jobs.first().job
        failedJob.status shouldBeEqualTo ImageJobStatus.FAILED
        failedJob.errorCode shouldBeEqualTo "S3_UPLOAD_ERROR"
    }
}
