package io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectDTO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectInput
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.mapper.toImageObjectDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageObjectTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

/**
 * Repository for [ImageObjectTable] rows.
 *
 * ## Behavior / Contract
 * - All write methods must be called within a [io.bluetape4k.exposed.core.auditable.UserContext.withUser] block.
 * - All methods must be called inside an Exposed `transaction {}` or equivalent.
 * - [batchUpsertObjects] resolves conflicts using the NULLS NOT DISTINCT unique index
 *   on `(image_asset_id, kind, variant_name)` created by [io.bluetape4k.workshop.imageprocessing.advanced.persistence.config.ImagePersistenceDatabaseInitializer].
 */
@Repository
class ImageObjectRepository : LongAuditableJdbcRepository<ImageObjectDTO, ImageObjectTable> {

    override val table = ImageObjectTable

    override fun extractId(entity: ImageObjectDTO): Long = entity.id

    override fun ResultRow.toEntity(): ImageObjectDTO = toImageObjectDTO()

    /**
     * Batch-upserts image objects associated with [assetId].
     *
     * Conflict resolution is driven by the unique index on
     * `(image_asset_id, kind, variant_name)` with NULLS NOT DISTINCT semantics.
     * When a duplicate row is found, all non-key columns are updated.
     *
     * @param assetId the parent asset's primary key
     * @param objects the list of image objects to persist
     */
    fun batchUpsertObjects(assetId: Long, objects: List<ImageObjectInput>) {
        if (objects.isEmpty()) return
        table.batchUpsert(
            objects,
            ImageObjectTable.imageAssetId,
            ImageObjectTable.kind,
            ImageObjectTable.variantName,
        ) { obj ->
            this[ImageObjectTable.imageAssetId] = assetId
            this[ImageObjectTable.kind] = obj.kind
            this[ImageObjectTable.variantName] = obj.variantName
            this[ImageObjectTable.s3Key] = obj.s3Key
            this[ImageObjectTable.publicUrl] = obj.publicUrl
            this[ImageObjectTable.width] = obj.width
            this[ImageObjectTable.height] = obj.height
            this[ImageObjectTable.byteSize] = obj.byteSize
            this[ImageObjectTable.format] = obj.format
        }
    }

    /**
     * Returns all image objects for the asset identified by [assetId].
     */
    fun findByAssetId(assetId: Long): List<ImageObjectDTO> =
        table.selectAll()
            .where { ImageObjectTable.imageAssetId eq assetId }
            .map { it.toImageObjectDTO() }
}
