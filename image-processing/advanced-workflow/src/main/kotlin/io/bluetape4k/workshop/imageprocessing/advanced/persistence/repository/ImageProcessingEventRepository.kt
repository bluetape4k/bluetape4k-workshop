package io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingEventDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.mapper.toImageProcessingEventDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventTable
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

/**
 * [ImageProcessingEventTable] 행 저장소입니다.
 *
 * ## 동작 / 계약
 * - 모든 메서드는 Exposed `transaction {}` 또는 동등한 경계 안에서 호출해야 합니다.
 * - [ImageProcessingEventTable.createdAt]은 DB 기본값(`CURRENT_TIMESTAMP`)으로 자동 설정됩니다.
 * - [appendEvent]의 예외는 호출자에게 전파되며 이 메서드 안에서는 억제하지 않습니다.
 */
@Repository
class ImageProcessingEventRepository: LongJdbcRepository<ImageProcessingEventDTO> {

    private companion object: KLogging()
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = ImageProcessingEventTable

    /**
     * extractId은 이미지 업로드·검수·파생물 처리 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: ImageProcessingEventDTO): Long = entity.id

    /**
     * toEntity은 이미지 업로드·검수·파생물 처리 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): ImageProcessingEventDTO = toImageProcessingEventDTO()

    /**
     * 지정한 [jobId]에 처리 이벤트 행 하나를 추가합니다.
     *
     * [ImageProcessingEventTable.createdAt]은 DB 기본값으로 채웁니다.
     * 예외는 호출자에게 전파되며 억제하지 않습니다.
     * @param jobId 부모 job의 기본 키입니다.
     * @param step 이 이벤트가 설명하는 파이프라인 단계입니다.
     * @param status 해당 단계의 결과입니다.
     * @param message 선택적인 사람이 읽을 수 있는 설명입니다.
     * @param payload 선택적인 구조화 진단 데이터입니다. 비어 있으면 null입니다.
     */
    fun appendEvent(
        jobId: Long,
        step: ImageProcessingStep,
        status: ImageProcessingEventStatus,
        message: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        log.debug { "append event. jobId=$jobId, step=$step, message=$message, payload=$payload" }
        ImageProcessingEventTable.insert {
            it[ImageProcessingEventTable.jobId] = jobId
            it[ImageProcessingEventTable.step] = step
            it[ImageProcessingEventTable.status] = status
            it[ImageProcessingEventTable.message] = message
            it[payloadJson] = payload.ifEmpty { null }
        }
    }

    /**
     * [jobId]로 식별한 job의 모든 이벤트를 [ImageProcessingEventTable.createdAt] ASC 순서로 반환합니다.
     */
    fun findByJobId(jobId: Long): List<ImageProcessingEventDTO> =
        ImageProcessingEventTable.selectAll()
            .where { ImageProcessingEventTable.jobId eq jobId }
            .orderBy(ImageProcessingEventTable.createdAt, SortOrder.ASC)
            .map { it.toImageProcessingEventDTO() }
            .onEach { log.debug { "jobId=$jobId, found event=$it" } }

}
