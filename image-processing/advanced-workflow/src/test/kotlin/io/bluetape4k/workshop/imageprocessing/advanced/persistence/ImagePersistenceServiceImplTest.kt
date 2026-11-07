package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobFailureReason
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobIdentity
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * [ImagePersistenceService] 통합 테스트입니다. 모든 사가 단계와 조회 경로를 다룹니다.
 *
 * ## 격리 전략
 * 각 테스트는 고유 checksum을 사용하므로 행을 공유하지 않습니다.
 * 여기서는 transaction rollback을 사용하지 않습니다. [ImagePersistenceServiceImpl]이
 * 자체 PROPAGATION_REQUIRES_NEW 트랜잭션 안에서 commit하기 때문입니다.
 */
class ImagePersistenceServiceImplTest: AbstractImagePersistenceTest() {

    @Autowired
    private val service: ImagePersistenceService = uninitialized()

    // -------------------------------------------------------------------------
    // T1 — recordJobStart 변형
    // -------------------------------------------------------------------------

    @Test
    fun `recordJobStart - new asset creates NewAsset result`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val metadata = buildMetadata(checksum = checksum)

        val result = service.recordJobStart(metadata)

        log.info { "recordJobStart result: $result" }
        result.shouldBeInstanceOf<JobStartResult.NewAsset>()
        result.assetId shouldBeGreaterThan 0L
        result.jobId shouldBeGreaterThan 0L
        result.externalId.shouldNotBeBlank()
    }

    @Test
    fun `recordJobStart - duplicate checksum READY returns AlreadyReady`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val metadata = buildMetadata(checksum = checksum)

        val firstResult = service.recordJobStart(metadata)
        firstResult.shouldBeInstanceOf<JobStartResult.NewAsset>()
        log.debug { "first recordJobStart result: $firstResult" }

        val firstIdentity = JobIdentity(jobId = firstResult.jobId, assetId = firstResult.assetId)

        service.recordJobSuccess(firstIdentity, buildObjects(firstResult.assetId))

        val secondResult = service.recordJobStart(metadata)
        log.debug { "second recordJobStart result: $secondResult" }
        secondResult.shouldBeInstanceOf<JobStartResult.AlreadyReady>()
        secondResult.jobId shouldBeEqualTo -1L
        secondResult.externalId shouldBeEqualTo firstResult.externalId
    }

    @Test
    fun `recordJobStart - duplicate checksum PROCESSING returns ConcurrentProcessing`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val metadata = buildMetadata(checksum = checksum)

        val firstResult = service.recordJobStart(metadata)
        firstResult.shouldBeInstanceOf<JobStartResult.NewAsset>()
        log.debug { "first recordJobStart result: $firstResult" }

        val secondResult = service.recordJobStart(metadata)
        log.debug { "second recordJobStart result: $secondResult" }
        secondResult.shouldBeInstanceOf<JobStartResult.ConcurrentProcessing>()
        secondResult.assetId shouldBeEqualTo firstResult.assetId
        secondResult.externalId shouldBeEqualTo firstResult.externalId
        secondResult.jobId shouldBeGreaterThan 0L
    }

    @Test
    fun `recordJobStart - duplicate checksum FAILED returns RecoveredFromFailed`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val metadata = buildMetadata(checksum = checksum)

        val firstResult = service.recordJobStart(metadata)
        firstResult.shouldBeInstanceOf<JobStartResult.NewAsset>()
        log.debug { "first recordJobStart result: $firstResult" }

        val firstIdentity = JobIdentity(jobId = firstResult.jobId, assetId = firstResult.assetId)

        service.recordJobFailure(
            firstIdentity,
            JobFailureReason(errorCode = "TEST_ERROR", errorMessage = "Test failure"),
            durationMs = 100L,
        )

        val recoveredResult = service.recordJobStart(metadata)
        log.debug { "recovered recordJobStart result: $recoveredResult" }
        recoveredResult.shouldBeInstanceOf<JobStartResult.RecoveredFromFailed>()
        recoveredResult.assetId shouldBeEqualTo firstResult.assetId
        recoveredResult.externalId shouldBeEqualTo firstResult.externalId
        recoveredResult.jobId shouldBeGreaterThan 0L
    }

    // -------------------------------------------------------------------------
    // T2 — recordJobSuccess
    // -------------------------------------------------------------------------

    @Test
    fun `recordJobSuccess - updates asset READY and persists image objects`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        startResult.shouldBeInstanceOf<JobStartResult.NewAsset>()

        val identity = JobIdentity(jobId = startResult.jobId, assetId = startResult.assetId)
        service.recordJobSuccess(identity, buildObjects(startResult.assetId))

        val detail = service.findAssetByExternalId(startResult.externalId).shouldNotBeNull()
        log.debug { "detail result: $detail" }
        detail.status shouldBeEqualTo ImageAssetStatus.READY
        detail.original.shouldNotBeNull()
        detail.variants shouldHaveSize 1
        detail.variants.single().variantName shouldBeEqualTo "thumbnail"
    }

    // -------------------------------------------------------------------------
    // T3 — recordJobFailure
    // -------------------------------------------------------------------------

    @Test
    fun `recordJobFailure - updates asset FAILED`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        startResult.shouldBeInstanceOf<JobStartResult.NewAsset>()

        val identity = JobIdentity(jobId = startResult.jobId, assetId = startResult.assetId)
        service.recordJobFailure(
            identity,
            JobFailureReason(errorCode = "VIPS_ERROR", errorMessage = "libvips failed to decode image"),
            durationMs = 250L,
        )

        val detail = service.findAssetByExternalId(startResult.externalId).shouldNotBeNull()
        log.debug { "detail result: $detail" }
        detail.status shouldBeEqualTo ImageAssetStatus.FAILED
    }

    // -------------------------------------------------------------------------
    // appendEvent
    // -------------------------------------------------------------------------

    @Test
    fun `appendEvent - stores event row`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        startResult.shouldBeInstanceOf<JobStartResult.NewAsset>()

        service.appendEvent(
            jobId = startResult.jobId,
            step = ImageProcessingStep.VALIDATION,
            status = ImageProcessingEventStatus.COMPLETED,
            message = "Validation passed",
        )

        val history = service.findAssetHistory(startResult.externalId).shouldNotBeNull()
        log.debug { "history result: $history" }
        history.jobs shouldHaveSize 1
        history.jobs.single().events shouldHaveSize 1
    }

    // -------------------------------------------------------------------------
    // findAssetByExternalId — null 경로
    // -------------------------------------------------------------------------

    @Test
    fun `findAssetByExternalId - returns null for unknown externalId`() {
        service.findAssetByExternalId(Base58.randomString(12)).shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // findAssetHistory — null 경로
    // -------------------------------------------------------------------------

    @Test
    fun `findAssetHistory - returns null for unknown externalId`() {
        service.findAssetHistory(Base58.randomString(12)).shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // findAssetHistory — 전체 작업과 이벤트 이력
    // -------------------------------------------------------------------------

    @Test
    fun `findAssetHistory - returns full job and event history`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val startResult = service.recordJobStart(buildMetadata(checksum = checksum))
        startResult.shouldBeInstanceOf<JobStartResult.NewAsset>()
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

        val history = service.findAssetHistory(startResult.externalId).shouldNotBeNull()
        log.debug { "history=$history" }
        history.imageId shouldBeEqualTo startResult.externalId
        history.jobs shouldHaveSize 1
        history.jobs.single().events shouldHaveSize 2
    }
}
