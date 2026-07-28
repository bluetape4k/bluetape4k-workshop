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
 * [ImageAssetTable] [ResultRow]를 [ImageAssetDTO]로 매핑합니다.
 *
 * ## 동작 / 계약
 * - [ImageAssetTable.createdAt]과 [updatedAt]은 `Instant?`이며 UTC offset으로
 *   [java.time.Instant.atOffset]을 거쳐 `LocalDateTime?`으로 변환합니다.
 * - [ImageAssetTable.id]는 `EntityID<Long>`이며 `.value`로 꺼냅니다.
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
 * [ImageObjectTable] [ResultRow]를 [ImageObjectDTO]로 매핑합니다.
 *
 * ## 동작 / 계약
 * - [ImageObjectTable.imageAssetId]는 `EntityID<Long>` FK이며 `.value`로 꺼냅니다.
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
 * [ImageProcessingJobTable] [ResultRow]를 [ImageProcessingJobDTO]로 매핑합니다.
 *
 * ## 동작 / 계약
 * - [ImageProcessingJobTable.startedAt]은 non-null `Instant`이며 `LocalDateTime`(UTC)으로 변환합니다.
 * - [ImageProcessingJobTable.finishedAt]은 nullable `Instant?`이며 `LocalDateTime?`(UTC)으로 변환합니다.
 * - [ImageProcessingJobTable.requestedVariants]는 `jacksonb` 컬럼이 JSONB에서 역직렬화합니다.
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
 * [ImageProcessingEventTable] [ResultRow]를 [ImageProcessingEventDTO]로 매핑합니다.
 *
 * ## 동작 / 계약
 * - [ImageProcessingEventTable.createdAt]은 non-null `Instant`이며 `LocalDateTime`(UTC)으로 변환합니다.
 * - [ImageProcessingEventTable.payloadJson]은 nullable JSONB이므로 null일 수 있습니다.
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
