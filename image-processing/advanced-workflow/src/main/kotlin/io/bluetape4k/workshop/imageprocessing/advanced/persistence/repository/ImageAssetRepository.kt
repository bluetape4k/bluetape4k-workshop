package io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.mapper.toImageAssetDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

/**
 * [ImageAssetTable] 행 저장소입니다.
 *
 * ## 동작 / 계약
 * - 모든 쓰기 메서드는 [io.bluetape4k.exposed.core.auditable.UserContext.withUser] 블록 안에서 호출해야 합니다.
 *   그래야 감사 컬럼(`created_by`, `updated_by`)이 올바르게 채워집니다.
 * - 모든 메서드는 Exposed `transaction {}` 또는 동등한 경계 안에서 호출해야 합니다.
 */
@Repository
class ImageAssetRepository : LongAuditableJdbcRepository<ImageAssetDTO, ImageAssetTable> {

    override val table = ImageAssetTable

    override fun extractId(entity: ImageAssetDTO): Long = entity.id

    override fun ResultRow.toEntity(): ImageAssetDTO = toImageAssetDTO()

    /**
     * 지정한 [checksum]을 가진 asset을 반환하며, 없으면 null을 반환합니다.
     *
     * 새 asset을 삽입하기 전에 중복 제거에 사용합니다.
     */
    fun findByChecksum(checksum: String): ImageAssetDTO? =
        table.selectAll()
            .where { ImageAssetTable.checksum eq checksum }
            .singleOrNull()
            ?.toImageAssetDTO()

    /**
     * 지정한 [externalId](UUID v4 문자열)를 가진 asset을 반환하며, 없으면 null을 반환합니다.
     */
    fun findByExternalId(externalId: String): ImageAssetDTO? =
        table.selectAll()
            .where { ImageAssetTable.externalId eq externalId }
            .singleOrNull()
            ?.toImageAssetDTO()

    /**
     * [id]로 식별한 asset의 [status]를 갱신합니다.
     *
     * [auditedUpdateById]를 사용해 `updated_by`와 `updated_at`이 자동으로 설정되게 합니다.
     */
    fun updateStatus(id: Long, status: ImageAssetStatus) {
        auditedUpdateById(id) {
            it[ImageAssetTable.status] = status
        }
    }
}
