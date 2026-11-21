package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobIdentity
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * [ImageAssetRepository] 저수준 작업의 통합 테스트입니다.
 *
 * [ImagePersistenceService.recordJobStart]를 사용해 실제에 가까운 방식으로 행을 만들고
 * 저장소를 직접 호출하지 않고 [UserContext]와 감사를 반영한 방식으로 행을 만듭니다.
 * 저장소 직접 호출에 필요한 열린 트랜잭션과 수동 [UserContext] 설정을 피합니다.
 */
class ImageAssetRepositoryTest: AbstractImagePersistenceTest() {

    private companion object: KLogging()

    @Autowired
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service: ImagePersistenceService = uninitialized()

    @Test
/**
 * 이 시나리오는 "findByExternalId - returns null when asset does not exist" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `findByExternalId - returns null when asset does not exist`() {
        val result = service.findAssetByExternalId(Base58.randomString(12))
        result.shouldBeNull()
    }

    @Test
/**
 * 이 시나리오는 "findByExternalId - returns asset after creation via recordJobStart" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `findByExternalId - returns asset after creation via recordJobStart`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val metadata = buildMetadata(checksum = checksum)

        val startResult = service.recordJobStart(metadata)
        log.debug { "start result=$startResult" }
        startResult.shouldBeInstanceOf<JobStartResult.NewAsset>()

        val detail = service.findAssetByExternalId(startResult.externalId).shouldNotBeNull()
        log.info { "Asset detail after recordJobStart: $detail" }
        detail.imageId shouldBeEqualTo startResult.externalId
    }

    @Test
/**
 * 이 시나리오는 "updateStatus - changes asset status to READY via recordJobSuccess" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `updateStatus - changes asset status to READY via recordJobSuccess`() {
        val checksum = "sha256-${Base58.randomString(12)}"
        val metadata = buildMetadata(checksum = checksum)

        val startResult = service.recordJobStart(metadata)
        log.debug { "start result=$startResult" }
        startResult.shouldBeInstanceOf<JobStartResult.NewAsset>()

        val identity = JobIdentity(
            jobId = startResult.jobId,
            assetId = startResult.assetId,
        )
        service.recordJobSuccess(identity, buildObjects(startResult.assetId))

        val detail = service.findAssetByExternalId(startResult.externalId).shouldNotBeNull()
        log.info { "Asset detail after recordJobSuccess: $detail" }
        detail.status shouldBeEqualTo ImageAssetStatus.READY
    }
}
