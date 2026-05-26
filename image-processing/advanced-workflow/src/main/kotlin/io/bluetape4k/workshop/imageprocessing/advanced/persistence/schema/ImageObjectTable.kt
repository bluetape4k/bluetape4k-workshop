package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectKind
import org.jetbrains.exposed.v1.core.ReferenceOption

/**
 * Exposed table for image objects (original and variant files stored in S3).
 *
 * Each row is linked to an [ImageAssetTable] row via [imageAssetId] with ON DELETE CASCADE.
 *
 * A partial unique constraint on `(image_asset_id, kind, variant_name) NULLS NOT DISTINCT`
 * is created via raw SQL in [ImagePersistenceDatabaseInitializer] — do NOT add an Exposed
 * `uniqueIndex()` here, because Exposed cannot express NULLS NOT DISTINCT.
 *
 * ## Inherited auditing columns (from [AuditableLongIdTable])
 * - `created_by`, `created_at`, `updated_by`, `updated_at`
 */
object ImageObjectTable : AuditableLongIdTable("image_objects") {

    /** FK to the parent [ImageAssetTable]; cascades deletes. */
    val imageAssetId = reference("image_asset_id", ImageAssetTable, onDelete = ReferenceOption.CASCADE)
        .index()

    /** Whether this object is the [ImageObjectKind.ORIGINAL] or a [ImageObjectKind.VARIANT]. */
    val kind = enumerationByName("kind", 20, ImageObjectKind::class)

    /** Variant name (e.g. `"thumbnail"`, `"webp-2x"`); null for originals. */
    val variantName = varchar("variant_name", 100).nullable()

    /** S3 object key under which the file is stored. */
    val s3Key = varchar("s3_key", 512)

    /** Publicly accessible URL for the object. */
    val publicUrl = text("public_url")

    /** Width in pixels; nullable. */
    val width = integer("width").nullable()

    /** Height in pixels; nullable. */
    val height = integer("height").nullable()

    /** Byte size of this object; nullable. */
    val byteSize = long("byte_size").nullable()

    /** Image format string (e.g. `"jpeg"`, `"webp"`, `"png"`); nullable. */
    val format = varchar("format", 20).nullable()
}
