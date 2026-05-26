package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable

/**
 * Exposed table for image assets.
 *
 * Each row represents one unique uploaded image, identified by its [checksum].
 * Deduplication is enforced by the UNIQUE index on [checksum].
 *
 * ## Inherited auditing columns (from [AuditableLongIdTable])
 * - `created_by` — set via [UserContext] on INSERT
 * - `created_at` — DB `CURRENT_TIMESTAMP` on INSERT
 * - `updated_by` — set on audited UPDATE
 * - `updated_at` — DB `CURRENT_TIMESTAMP` on audited UPDATE
 */
object ImageAssetTable : AuditableLongIdTable("image_assets") {

    /** UUID v4 string exposed to callers as the public image identifier. */
    val externalId = varchar("external_id", 36).uniqueIndex()

    /** Original filename provided by the uploader; nullable when not supplied. */
    val originalFilename = varchar("original_filename", 255).nullable()

    /** MIME type of the uploaded file (e.g. `image/jpeg`); nullable. */
    val contentType = varchar("content_type", 100).nullable()

    /** Raw byte size of the original upload; nullable. */
    val byteSize = long("byte_size").nullable()

    /** Width in pixels of the original image; nullable. */
    val width = integer("width").nullable()

    /** Height in pixels of the original image; nullable. */
    val height = integer("height").nullable()

    /** SHA-256 or MD5 hex digest used for deduplication. */
    val checksum = varchar("checksum", 64).uniqueIndex()

    /** Current lifecycle status of this asset. */
    val status = enumerationByName("status", 20, ImageAssetStatus::class)
        .default(ImageAssetStatus.PROCESSING)
}
