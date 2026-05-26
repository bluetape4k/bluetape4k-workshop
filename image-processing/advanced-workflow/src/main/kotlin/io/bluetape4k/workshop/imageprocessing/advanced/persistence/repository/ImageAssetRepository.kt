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
 * Repository for [ImageAssetTable] rows.
 *
 * ## Behavior / Contract
 * - All write methods must be called within a [io.bluetape4k.exposed.core.auditable.UserContext.withUser] block
 *   so that audit columns (`created_by`, `updated_by`) are populated correctly.
 * - All methods must be called inside an Exposed `transaction {}` or equivalent.
 */
@Repository
class ImageAssetRepository : LongAuditableJdbcRepository<ImageAssetDTO, ImageAssetTable> {

    override val table = ImageAssetTable

    override fun extractId(entity: ImageAssetDTO): Long = entity.id

    override fun ResultRow.toEntity(): ImageAssetDTO = toImageAssetDTO()

    /**
     * Returns the asset with the given [checksum], or null if not found.
     *
     * Used for deduplication before inserting a new asset.
     */
    fun findByChecksum(checksum: String): ImageAssetDTO? =
        table.selectAll()
            .where { ImageAssetTable.checksum eq checksum }
            .singleOrNull()
            ?.toImageAssetDTO()

    /**
     * Returns the asset with the given [externalId] (UUID v4 string), or null if not found.
     */
    fun findByExternalId(externalId: String): ImageAssetDTO? =
        table.selectAll()
            .where { ImageAssetTable.externalId eq externalId }
            .singleOrNull()
            ?.toImageAssetDTO()

    /**
     * Updates the [status] of the asset identified by [id].
     *
     * Uses [auditedUpdateById] so that `updated_by` and `updated_at` are set automatically.
     */
    fun updateStatus(id: Long, status: ImageAssetStatus) {
        auditedUpdateById(id) {
            it[ImageAssetTable.status] = status
        }
    }
}
