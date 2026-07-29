package io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
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
class ImageProcessingEventRepository : LongJdbcRepository<ImageProcessingEventDTO> {

    override val table = ImageProcessingEventTable

    override fun extractId(entity: ImageProcessingEventDTO): Long = entity.id

    override fun ResultRow.toEntity(): ImageProcessingEventDTO = toImageProcessingEventDTO()

    /**
     * 지정한 [jobId]에 처리 이벤트 행 하나를 추가합니다.
     *
     * [ImageProcessingEventTable.createdAt]은 DB 기본값으로 채웁니다.
     * 예외는 호출자에게 전파되며 억제하지 않습니다.
     *
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
}
