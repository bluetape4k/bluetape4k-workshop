package io.bluetape4k.workshop.imageprocessing.advanced.persistence.mapper

import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDTO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectDTO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectKind
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingEventDTO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingJobDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetTable
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageObjectTable
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventTable
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingJobTable
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Maps an [ImageAssetTable] [ResultRow] to an [ImageAssetDTO].
 *
 * ## Behavior / Contract
 * - [ImageAssetTable.createdAt] and [updatedAt] are `Instant?`; converted to
 *   `LocalDateTime?` via [java.time.Instant.atOffset] with UTC offset.
 * - [ImageAssetTable.id] is an `EntityID<Long>`; unwrapped via `.value`.
 */
fun ResultRow.toImageAssetDTO(): ImageAssetDTO = ImageAssetDTO(
    id = this[ImageAssetTable.id].value,
    externalId = this[ImageAssetTable.externalId],
    originalFilename = this[ImageAssetTable.originalFilename],
    contentType = this[ImageAssetTable.contentType],
    byteSize = this[ImageAssetTable.byteSize],
    width = this[ImageAssetTable.width],
    height = this[ImageAssetTable.height],
    checksum = this[ImageAssetTable.checksum],
    status = this[ImageAssetTable.status],
    createdAt = this[ImageAssetTable.createdAt]
        ?.atOffset(java.time.ZoneOffset.UTC)?.toLocalDateTime(),
    updatedAt = this[ImageAssetTable.updatedAt]
        ?.atOffset(java.time.ZoneOffset.UTC)?.toLocalDateTime(),
)

/**
 * Maps an [ImageObjectTable] [ResultRow] to an [ImageObjectDTO].
 *
 * ## Behavior / Contract
 * - [ImageObjectTable.imageAssetId] is an `EntityID<Long>` FK; unwrapped via `.value`.
 */
fun ResultRow.toImageObjectDTO(): ImageObjectDTO = ImageObjectDTO(
    id = this[ImageObjectTable.id].value,
    imageAssetId = this[ImageObjectTable.imageAssetId].value,
    kind = this[ImageObjectTable.kind],
    variantName = this[ImageObjectTable.variantName],
    s3Key = this[ImageObjectTable.s3Key],
    publicUrl = this[ImageObjectTable.publicUrl],
    width = this[ImageObjectTable.width],
    height = this[ImageObjectTable.height],
    byteSize = this[ImageObjectTable.byteSize],
    format = this[ImageObjectTable.format],
)

/**
 * Maps an [ImageProcessingJobTable] [ResultRow] to an [ImageProcessingJobDTO].
 *
 * ## Behavior / Contract
 * - [ImageProcessingJobTable.startedAt] is non-nullable `Instant`; converted to `LocalDateTime` (UTC).
 * - [ImageProcessingJobTable.finishedAt] is nullable `Instant?`; converted to `LocalDateTime?` (UTC).
 * - [ImageProcessingJobTable.requestedVariants] is deserialized from JSONB by the `jacksonb` column.
 */
fun ResultRow.toImageProcessingJobDTO(): ImageProcessingJobDTO = ImageProcessingJobDTO(
    id = this[ImageProcessingJobTable.id].value,
    imageAssetId = this[ImageProcessingJobTable.imageAssetId].value,
    status = this[ImageProcessingJobTable.status],
    requestedVariants = this[ImageProcessingJobTable.requestedVariants],
    startedAt = this[ImageProcessingJobTable.startedAt]
        .atOffset(java.time.ZoneOffset.UTC).toLocalDateTime(),
    finishedAt = this[ImageProcessingJobTable.finishedAt]
        ?.atOffset(java.time.ZoneOffset.UTC)?.toLocalDateTime(),
    durationMs = this[ImageProcessingJobTable.durationMs],
    errorCode = this[ImageProcessingJobTable.errorCode],
    errorMessage = this[ImageProcessingJobTable.errorMessage],
)

/**
 * Maps an [ImageProcessingEventTable] [ResultRow] to an [ImageProcessingEventDTO].
 *
 * ## Behavior / Contract
 * - [ImageProcessingEventTable.createdAt] is non-nullable `Instant`; converted to `LocalDateTime` (UTC).
 * - [ImageProcessingEventTable.payloadJson] is nullable JSONB; may be null.
 */
fun ResultRow.toImageProcessingEventDTO(): ImageProcessingEventDTO = ImageProcessingEventDTO(
    id = this[ImageProcessingEventTable.id].value,
    jobId = this[ImageProcessingEventTable.jobId].value,
    step = this[ImageProcessingEventTable.step],
    status = this[ImageProcessingEventTable.status],
    message = this[ImageProcessingEventTable.message],
    payloadJson = this[ImageProcessingEventTable.payloadJson],
    createdAt = this[ImageProcessingEventTable.createdAt]
        .atOffset(java.time.ZoneOffset.UTC).toLocalDateTime(),
)
