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
 * [ImagePersistenceService]가 노출하는 처리 job 이력 경로의 통합 테스트입니다.
 *
 * job 생성, 성공/실패 표시, [ImagePersistenceService.findAssetHistory]
 * 쿼리를 검증합니다. 이 쿼리는 `GET /images/{imageId}/history`를 구동합니다.
 *
 * ## 격리 전략
 * 각 테스트는 고유 checksum을 사용합니다. 서비스 내부의 `PROPAGATION_REQUIRES_NEW` commit이
 * `@Rollback`을 무력화하므로 테스트별 고유 키가 유일하게 안전한 격리 방식입니다.
 */
class ImageProcessingJobRepositoryTest : AbstractImagePersistenceTest() {

    @Autowired
    private lateinit var service: ImagePersistenceService

    @Test
    fun `findAssetHistory - returns null for unknown externalId`() {
        val history = service.findAssetHistory(Base58.randomString(12))
        // Soft assert: 알 수 없는 asset에는 이력이 없으므로 result는 null입니다.
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
