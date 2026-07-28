package io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingJobDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.mapper.toImageProcessingJobDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageJobStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingJobTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository

/**
 * [ImageProcessingJobTable] 행 저장소입니다.
 *
 * ## 동작 / 계약
 * - 모든 메서드는 Exposed `transaction {}` 또는 동등한 경계 안에서 호출해야 합니다.
 * - 감사 컬럼은 없습니다. 시간은 [ImageProcessingJobTable.startedAt]
 *   및 [ImageProcessingJobTable.finishedAt]으로 추적합니다.
 */
@Repository
class ImageProcessingJobRepository : LongJdbcRepository<ImageProcessingJobDTO> {

    override val table = ImageProcessingJobTable

    override fun extractId(entity: ImageProcessingJobDTO): Long = entity.id

    override fun ResultRow.toEntity(): ImageProcessingJobDTO = toImageProcessingJobDTO()

    /**
     * [assetId]에 RUNNING 상태의 새 처리 job을 삽입하고 새 job ID를 반환합니다.
     *
     * [ImageProcessingJobTable.startedAt]은 DB 기본값(`CURRENT_TIMESTAMP`)으로 설정됩니다.
     *
     * @param assetId 부모 asset의 기본 키입니다.
     * @param requestedVariants 이 job이 요청한 변형 이름 목록입니다. 비어 있을 수 있습니다.
     * @return 생성된 job 기본 키입니다.
     */
    fun insertJob(assetId: Long, requestedVariants: List<String>): Long =
        ImageProcessingJobTable.insertAndGetId {
            it[ImageProcessingJobTable.imageAssetId] = assetId
            it[ImageProcessingJobTable.status] = ImageJobStatus.RUNNING
            it[ImageProcessingJobTable.requestedVariants] = requestedVariants
        }.value

    /**
     * [jobId]로 식별한 job을 SUCCEEDED로 표시합니다.
     *
     * [ImageProcessingJobTable.finishedAt]을 `CURRENT_TIMESTAMP`로 설정하고
     * 관측성을 위해 [durationMs]를 저장합니다.
     */
    fun markSucceeded(jobId: Long, durationMs: Long) {
        ImageProcessingJobTable.update({ ImageProcessingJobTable.id eq jobId }) {
            it[status] = ImageJobStatus.SUCCEEDED
            it[finishedAt] = CurrentTimestamp
            it[ImageProcessingJobTable.durationMs] = durationMs
        }
    }

    /**
     * [jobId]로 식별한 job을 FAILED로 표시합니다.
     *
     * 정제된 [errorCode]와 [errorMessage]를 시간 정보와 함께 저장합니다.
     * 오류 문자열을 전달하기 전에 정제하는 책임은 호출자에게 있습니다.
     */
    fun markFailed(jobId: Long, errorCode: String, errorMessage: String, durationMs: Long) {
        ImageProcessingJobTable.update({ ImageProcessingJobTable.id eq jobId }) {
            it[status] = ImageJobStatus.FAILED
            it[finishedAt] = CurrentTimestamp
            it[ImageProcessingJobTable.durationMs] = durationMs
            it[ImageProcessingJobTable.errorCode] = errorCode
            it[ImageProcessingJobTable.errorMessage] = errorMessage
        }
    }

    /**
     * [assetId]로 식별한 asset의 모든 job을 [ImageProcessingJobTable.startedAt] DESC 순서로 반환합니다.
     */
    fun findByAssetId(assetId: Long): List<ImageProcessingJobDTO> =
        ImageProcessingJobTable.selectAll()
            .where { ImageProcessingJobTable.imageAssetId eq assetId }
            .orderBy(ImageProcessingJobTable.startedAt, SortOrder.DESC)
            .map { it.toImageProcessingJobDTO() }
}
