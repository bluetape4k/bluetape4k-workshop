package io.bluetape4k.workshop.imageprocessing.advanced.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageJobStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import java.io.Serializable
import java.time.LocalDateTime

// ---------------------------------------------------------------------------
// Input value objects
// ---------------------------------------------------------------------------

/**
 * Caller-supplied metadata for starting a new image processing job.
 *
 * ## Behavior / Contract
 * - [checksum] must be non-blank; used as the deduplication key.
 * - [dimensions] may be null when not available at upload time.
 */
data class AssetMetadataInput(
    val checksum: String,
    val originalFilename: String?,
    val contentType: String?,
    val byteSize: Long?,
    val dimensions: ImageDimensions?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Pixel dimensions of an image.
 *
 * Wraps two adjacent `Int` parameters to avoid positional mistakes.
 */
data class ImageDimensions(
    val width: Int,
    val height: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Wraps two adjacent `Long` parameters (jobId, assetId) to prevent positional mistakes.
 *
 * ## Behavior / Contract
 * - Both [jobId] and [assetId] must be positive (> 0).
 */
data class JobIdentity(
    val jobId: Long,
    val assetId: Long,
) : Serializable {
    init {
        jobId.requirePositiveNumber("jobId")
        assetId.requirePositiveNumber("assetId")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Wraps two adjacent `String` parameters that describe a job failure.
 *
 * ## Behavior / Contract
 * - [errorCode] must be non-blank and at most 100 characters.
 * - [errorMessage] must be non-blank.
 */
data class JobFailureReason(
    val errorCode: String,
    val errorMessage: String,
) : Serializable {
    init {
        errorCode.requireNotBlank("errorCode")
        require(errorCode.length <= 100) { "errorCode must be ≤ 100 chars, was ${errorCode.length}" }
        errorMessage.requireNotBlank("errorMessage")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Input descriptor for one image object (original or variant) to be persisted.
 *
 * ## Behavior / Contract
 * - [kind] = [ImageObjectKind.ORIGINAL] → [variantName] is typically null.
 * - [kind] = [ImageObjectKind.VARIANT]  → [variantName] identifies the variant (e.g. `"thumbnail"`).
 */
data class ImageObjectInput(
    val kind: ImageObjectKind,
    val variantName: String?,
    val s3Key: String,
    val publicUrl: String,
    val width: Int?,
    val height: Int?,
    val byteSize: Long?,
    val format: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

// ---------------------------------------------------------------------------
// Sealed JobStartResult
// ---------------------------------------------------------------------------

/**
 * Exhaustive result returned by `ImagePersistenceService.startJob()`.
 *
 * ## Variants
 * - [NewAsset]              — new asset row created; caller must proceed with processing.
 * - [AlreadyReady]          — asset with READY status found; caller must return cached response.
 *   `jobId = -1L` — no new job was created.
 * - [ConcurrentProcessing]  — asset is already being processed by another request.
 * - [RecoveredFromFailed]   — previous FAILED asset was reset to PROCESSING; new job created.
 *
 * ## Behavior / Contract
 * - [externalId] is the UUID v4 string used as `imageId` in POST responses.
 * - [AlreadyReady] emits NO events — it is a pure short-circuit path.
 */
sealed interface JobStartResult : Serializable {
    val assetId: Long
    val jobId: Long

    /** UUID v4 string; used as `imageId` in API responses. */
    val externalId: String

    /** New asset row was created; processing should proceed normally. */
    data class NewAsset(
        override val assetId: Long,
        override val jobId: Long,
        override val externalId: String,
    ) : JobStartResult {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Asset with READY status already exists; caller must skip processing and return
     * the cached response. [jobId] = -1L (no new job was created).
     * NO events are emitted in this short-circuit path.
     */
    data class AlreadyReady(
        override val assetId: Long,
        override val jobId: Long = -1L,
        override val externalId: String,
    ) : JobStartResult {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /** Asset row exists and is currently being processed by another request. */
    data class ConcurrentProcessing(
        override val assetId: Long,
        override val jobId: Long,
        override val externalId: String,
    ) : JobStartResult {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Previous FAILED asset was recovered: status reset to PROCESSING, new job created.
     * Caller must proceed with full processing.
     */
    data class RecoveredFromFailed(
        override val assetId: Long,
        override val jobId: Long,
        override val externalId: String,
    ) : JobStartResult {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}

// ---------------------------------------------------------------------------
// DTOs (ResultRow → DTO mappers live in ImagePersistenceMappers.kt)
// ---------------------------------------------------------------------------

/**
 * Read-model DTO for an `image_assets` row.
 *
 * [createdAt] and [updatedAt] are nullable because [AuditableIdTable] declares them nullable.
 */
data class ImageAssetDTO(
    val id: Long,
    val externalId: String,
    val originalFilename: String?,
    val contentType: String?,
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
    val checksum: String,
    val status: ImageAssetStatus,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Read-model DTO for an `image_objects` row. */
data class ImageObjectDTO(
    val id: Long,
    val imageAssetId: Long,
    val kind: ImageObjectKind,
    val variantName: String?,
    val s3Key: String,
    val publicUrl: String,
    val width: Int?,
    val height: Int?,
    val byteSize: Long?,
    val format: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Read-model DTO for an `image_processing_jobs` row. */
data class ImageProcessingJobDTO(
    val id: Long,
    val imageAssetId: Long,
    val status: ImageJobStatus,
    val requestedVariants: List<String>,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val durationMs: Long?,
    val errorCode: String?,
    val errorMessage: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Read-model DTO for an `image_processing_events` row. */
data class ImageProcessingEventDTO(
    val id: Long,
    val jobId: Long,
    val step: ImageProcessingStep,
    val status: ImageProcessingEventStatus,
    val message: String?,
    val payloadJson: Map<String, Any?>?,
    val createdAt: LocalDateTime,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

// ---------------------------------------------------------------------------
// Response DTOs for GET endpoints
// ---------------------------------------------------------------------------

/**
 * Response for `GET /images/{imageId}` — asset detail with original and variants.
 *
 * [imageId] equals the asset's [externalId] (UUID v4 string).
 */
data class ImageAssetDetailResponse(
    val imageId: String,
    val status: ImageAssetStatus,
    val original: ImageObjectDTO?,
    val variants: List<ImageObjectDTO>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** A job paired with all its step events. */
data class ImageJobWithEventsDTO(
    val job: ImageProcessingJobDTO,
    val events: List<ImageProcessingEventDTO>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Response for `GET /images/{imageId}/history` — all jobs and their events for an asset.
 *
 * [imageId] equals the asset's [externalId] (UUID v4 string).
 */
data class ImageAssetHistoryResponse(
    val imageId: String,
    val jobs: List<ImageJobWithEventsDTO>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

// ---------------------------------------------------------------------------
// Custom exceptions
// ---------------------------------------------------------------------------

/** Thrown when no [ImageAssetTable] row can be found for the given [checksum]. */
class ImageAssetNotFoundException(val checksum: String) :
    NoSuchElementException("ImageAsset not found for checksum: $checksum")
