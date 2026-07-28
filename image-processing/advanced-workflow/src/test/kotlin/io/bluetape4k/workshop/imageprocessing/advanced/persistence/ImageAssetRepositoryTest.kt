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
 * [ImageAssetRepository] 저수준 작업의 통합 테스트입니다.
 *
 * [ImagePersistenceService.recordJobStart]를 사용해 실제에 가까운 방식으로 행을 만들고
 * 저장소를 직접 호출하지 않고 [UserContext]와 감사를 반영한 방식으로 행을 만듭니다.
 * 저장소 직접 호출에 필요한 열린 트랜잭션과 수동 [UserContext] 설정을 피합니다.
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
