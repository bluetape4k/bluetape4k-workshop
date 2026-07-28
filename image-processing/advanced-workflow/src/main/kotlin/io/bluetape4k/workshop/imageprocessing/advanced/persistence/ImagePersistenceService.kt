package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.workshop.imageprocessing.advanced.model.AssetMetadataInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDetailResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetHistoryResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobFailureReason
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobIdentity
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import org.springframework.transaction.support.TransactionTemplate

/**
 * 이미지 영속화 사가의 서비스 계약입니다.
 *
 * ## 트랜잭션 계약
 * 각 메서드는 프로그래밍 방식의 [TransactionTemplate]으로 자체 `REQUIRES_NEW`
 * 트랜잭션에서 실행됩니다. 이 인터페이스나 구현에는 `@Transactional`을 두지
 * 않으므로 호출자는 외부 트랜잭션에 참여한다고 가정하면 안 됩니다.
 *
 * ## 감사 사용자
 * 모든 쓰기 메서드는 [UserContext.withUser] 블록 안에서 호출해야 합니다.
 * 구현은 메서드 본문 전체를 감싸므로 호출자가 직접 사용자 컨텍스트를
 * 설정할 필요가 없습니다.
 */
interface ImagePersistenceService {

    /**
     * T1: 지정한 checksum/metadata로 새 처리 job을 시작합니다.
     *
     * 새 asset 생성 여부, 기존 READY asset 발견 여부 등을 설명하는 [JobStartResult]를
     * 반환합니다([JobStartResult.AlreadyReady] 포함).
     * 동시 insert에서 발생하는 [org.springframework.dao.DataIntegrityViolationException]을 처리합니다.
     */
    fun recordJobStart(metadata: AssetMetadataInput): JobStartResult

    /**
     * T2: job을 성공으로 표시하고 이미지 객체를 upsert한 뒤 asset 상태를 READY로 바꿉니다.
     */
    fun recordJobSuccess(identity: JobIdentity, objects: List<ImageObjectInput>)

    /**
     * T3: job을 실패로 표시하고 asset 상태를 FAILED로 바꿉니다.
     *
     * 정제된 오류 정보만 저장하며 원시 stack trace는 저장하지 않습니다.
     * [kotlinx.coroutines.NonCancellable] + [kotlinx.coroutines.Dispatchers.IO] 래핑은
     * 호출자의 책임입니다.
     */
    fun recordJobFailure(identity: JobIdentity, reason: JobFailureReason, durationMs: Long)

    /**
     * 처리 이벤트 행 하나를 추가합니다.
     *
     * 예외는 호출자에게 전파되며 구현 내부에서 억제하지 않습니다.
     */
    fun appendEvent(
        jobId: Long,
        step: ImageProcessingStep,
        status: ImageProcessingEventStatus,
        message: String,
        payload: Map<String, Any?> = emptyMap(),
    )

    /**
     * [externalId]로 asset 상세(원본 + 변형)를 반환합니다.
     *
     * 지정한 ID에 해당하는 asset이 없으면 null을 반환합니다.
     */
    fun findAssetByExternalId(externalId: String): ImageAssetDetailResponse?

    /**
     * [externalId]로 식별한 asset의 전체 job + 이벤트 이력을 반환합니다.
     *
     * asset이 없으면 null을 반환합니다.
     */
    fun findAssetHistory(externalId: String): ImageAssetHistoryResponse?
}
