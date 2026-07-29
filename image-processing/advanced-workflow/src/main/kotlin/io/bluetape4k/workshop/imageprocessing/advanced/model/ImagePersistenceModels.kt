package io.bluetape4k.workshop.imageprocessing.advanced.model

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageJobStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import java.io.Serializable
import java.time.LocalDateTime

// ---------------------------------------------------------------------------
// 입력 값 객체
// ---------------------------------------------------------------------------

/**
 * 새 이미지 처리 작업을 시작할 때 호출자가 제공하는 메타데이터입니다.
 *
 * ## 동작 / 계약
 * - [checksum]은 비어 있으면 안 되며 중복 제거 키로 사용합니다.
 * - 업로드 시점에 크기 정보를 알 수 없으면 [dimensions]는 null일 수 있습니다.
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
 * 이미지의 픽셀 크기입니다.
 *
 * 인접한 두 `Int` 파라미터의 위치 혼동을 막기 위한 래퍼입니다.
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
 * 인접한 두 `Long` 파라미터(jobId, assetId)의 위치 혼동을 막기 위한 래퍼입니다.
 *
 * ## 동작 / 계약
 * - [jobId]와 [assetId]는 모두 양수(> 0)여야 합니다.
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
 * 작업 실패를 설명하는 인접한 두 `String` 파라미터를 감싸는 래퍼입니다.
 *
 * ## 동작 / 계약
 * - [errorCode]는 비어 있으면 안 되며 최대 100자입니다.
 * - [errorMessage]는 비어 있으면 안 됩니다.
 */
data class JobFailureReason(
    val errorCode: String,
    val errorMessage: String,
) : Serializable {
    init {
        errorCode.requireNotBlank("errorCode")
        errorCode.length.requireInRange(1, 100, "errorCode.length")
        errorMessage.requireNotBlank("errorMessage")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 영속화할 이미지 객체 하나(원본 또는 변형)의 입력 설명자입니다.
 *
 * ## 동작 / 계약
 * - [kind] = [ImageObjectKind.ORIGINAL]이면 [variantName]은 보통 null입니다.
 * - [kind] = [ImageObjectKind.VARIANT]이면 [variantName]이 변형을 식별합니다(예: `"thumbnail"`).
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
// 봉인된 JobStartResult
// ---------------------------------------------------------------------------

/**
 * `ImagePersistenceService.startJob()`이 반환하는 전체 결과 집합입니다.
 *
 * ## 변형
 * - [NewAsset]              — 새 asset 행을 만들었으며 호출자는 처리를 계속해야 합니다.
 * - [AlreadyReady]          — READY 상태의 asset을 찾았으며 호출자는 캐시된 응답을 반환해야 합니다.
 *   `jobId = -1L` — 새 job을 만들지 않았다는 뜻입니다.
 * - [ConcurrentProcessing]  — 다른 요청이 이미 asset을 처리 중입니다.
 * - [RecoveredFromFailed]   — 이전 FAILED asset을 PROCESSING으로 되돌리고 새 job을 만들었습니다.
 *
 * ## 동작 / 계약
 * - [externalId]는 POST 응답의 `imageId`로 사용하는 UUID v4 문자열입니다.
 * - [AlreadyReady]는 이벤트를 발행하지 않는 순수 단축 경로입니다.
 */
sealed interface JobStartResult : Serializable {
    val assetId: Long
    val jobId: Long

    /** UUID v4 문자열이며 API 응답의 `imageId`로 사용합니다. */
    val externalId: String

    /** 새 asset 행이 생성되었으므로 처리를 정상적으로 계속해야 합니다. */
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
     * READY 상태의 asset이 이미 있으므로 호출자는 처리를 건너뛰고
     * 캐시된 응답을 반환해야 합니다. [jobId] = -1L은 새 job을 만들지 않았다는 뜻입니다.
     * 이 단축 경로에서는 이벤트를 발행하지 않습니다.
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

    /** asset 행이 있으며 다른 요청이 현재 처리 중입니다. */
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
     * 이전 FAILED asset을 복구해 상태를 PROCESSING으로 되돌리고 새 job을 만들었습니다.
     * 호출자는 전체 처리를 계속해야 합니다.
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
// DTO([ResultRow] → DTO 매퍼는 ImagePersistenceMappers.kt에 있음)
// ---------------------------------------------------------------------------

/**
 * `image_assets` 행의 읽기 모델 DTO입니다.
 *
 * [AuditableIdTable]이 nullable로 선언하므로 [createdAt]과 [updatedAt]도 nullable입니다.
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

/** `image_objects` 행의 읽기 모델 DTO입니다. */
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

/** `image_processing_jobs` 행의 읽기 모델 DTO입니다. */
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

/** `image_processing_events` 행의 읽기 모델 DTO입니다. */
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
// GET 엔드포인트 응답 DTO
// ---------------------------------------------------------------------------

/**
 * `GET /images/{imageId}` 응답입니다. 원본과 변형을 포함한 asset 상세를 담습니다.
 *
 * [imageId]는 asset의 [externalId](UUID v4 문자열)와 같습니다.
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

/** 모든 단계 이벤트와 묶인 job입니다. */
data class ImageJobWithEventsDTO(
    val job: ImageProcessingJobDTO,
    val events: List<ImageProcessingEventDTO>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `GET /images/{imageId}/history` 응답입니다. asset의 모든 job과 이벤트를 담습니다.
 *
 * [imageId]는 asset의 [externalId](UUID v4 문자열)와 같습니다.
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
// 사용자 정의 예외
// ---------------------------------------------------------------------------

/** 지정한 [checksum]에 해당하는 [ImageAssetTable] 행을 찾지 못했을 때 던집니다. */
class ImageAssetNotFoundException(val checksum: String) :
    NoSuchElementException("ImageAsset not found for checksum: $checksum")
