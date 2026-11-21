package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.codec.Base58
import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.ImageObjectMetadata
import io.bluetape4k.images.spring.ImageStorageException
import io.bluetape4k.images.spring.ImageUploadResult
import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.images.spring.storage.ImageObjectMetadataReader
import io.bluetape4k.images.spring.storage.ImageStorage
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageVariantProperties
import io.bluetape4k.workshop.imageprocessing.advanced.model.AssetMetadataInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDetailResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetHistoryResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobFailureReason
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobIdentity
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.model.OriginalImageInfo
import io.bluetape4k.workshop.imageprocessing.advanced.model.ProcessedImageSet
import io.bluetape4k.workshop.imageprocessing.advanced.model.ProcessedImageVariant
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.ImagePersistenceService
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * testProperties은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
internal fun testProperties(
    publicBaseUrl: String = "http://localhost:8080/public-images",
    maxInputBytes: Long = 1024,
    requestConcurrency: Int = 2,
    variantConcurrency: Int = 2,
    processingTimeout: Duration = Duration.ofSeconds(5),
    allowInsecurePublicBaseUrl: Boolean = false,
    allowLocalStorageRemotePublicBaseUrl: Boolean = false,
): ImageProcessingAdvancedProperties =
    ImageProcessingAdvancedProperties(
        publicBaseUrl = publicBaseUrl,
        allowInsecurePublicBaseUrl = allowInsecurePublicBaseUrl,
        allowLocalStorageRemotePublicBaseUrl = allowLocalStorageRemotePublicBaseUrl,
        maxInputBytes = maxInputBytes,
        requestConcurrency = requestConcurrency,
        variantConcurrency = variantConcurrency,
        processingTimeout = processingTimeout,
        variants = listOf(
            ImageVariantProperties(name = "thumb-128", maxDimension = 128, primaryThumbnail = true),
            ImageVariantProperties(name = "card-320", maxDimension = 320),
            ImageVariantProperties(name = "detail-1024", maxDimension = 1024),
        ),
    )

/**
 * SAMPLE_JPEG_BYTES은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
internal val SAMPLE_JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)

/**
 * RecordedUpload 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class RecordedUpload(
/**
 * key은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val key: ImageObjectKey,
/**
 * bytes은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val bytes: ByteArray,
/**
 * options은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val options: UploadOptions,
)

/**
 * RecordingImageStorage 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class RecordingImageStorage(
/**
 * failOnKeyPart은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val failOnKeyPart: String? = null,
): ImageStorage {

/**
 * uploads은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val uploads = mutableListOf<RecordedUpload>()
/**
 * deletes은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deletes = mutableListOf<ImageObjectKey>()
/**
 * downloads은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val downloads = mutableListOf<ImageObjectKey>()

/**
 * upload은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun upload(
        key: ImageObjectKey,
        bytes: ByteArray,
        options: UploadOptions,
    ): ImageUploadResult {
        if (failOnKeyPart != null && key.fullKey.contains(failOnKeyPart)) {
            throw ImageStorageException.TransientException(key = key, message = "forced failure: ${key.fullKey}")
        }
        uploads += RecordedUpload(key, bytes, options)
        return ImageUploadResult(
            key = key,
            etag = bytes.size.toString(),
            sizeBytes = bytes.size.toLong(),
            contentType = options.contentType,
            uploadedAt = Instant.EPOCH,
        )
    }

/**
 * upload은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun upload(
        key: ImageObjectKey,
        source: Path,
        options: UploadOptions,
    ): ImageUploadResult =
        upload(key, source.toFile().readBytes(), options)

/**
 * download은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun download(key: ImageObjectKey): ByteArray {
        downloads += key
        return ByteArray(0)
    }

/**
 * download은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun download(key: ImageObjectKey, destination: Path) {
        downloads += key
    }

/**
 * delete은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun delete(key: ImageObjectKey) {
        deletes += key
    }

/**
 * exists은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun exists(key: ImageObjectKey): Boolean = uploads.any { it.key == key }

/**
 * list은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun list(prefix: ImageObjectKey): Flow<ImageObjectKey> = emptyFlow()
}

/**
 * RecordedEvent 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class RecordedEvent(
/**
 * step은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val step: ImageProcessingStep,
/**
 * status은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val status: ImageProcessingEventStatus,
/**
 * message은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val message: String,
/**
 * payload은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val payload: Map<String, Any?>,
)

/** 선택적 metadata capability를 검증하기 위한 in-memory storage입니다. */
/** 선택적 metadata capability를 검증하기 위한 in-memory storage입니다. */
internal class RecordingMetadataImageStorage(
/**
 * delegate은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val delegate: RecordingImageStorage = RecordingImageStorage(),
/**
 * failOnMetadataKeyPart은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val failOnMetadataKeyPart: String? = null,
/**
 * metadataFn은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val metadataFn: (ImageObjectKey, RecordedUpload) -> ImageObjectMetadata = { key, upload ->
        ImageObjectMetadata(
            key = key,
            sizeBytes = upload.bytes.size.toLong(),
            etag = "\"opaque-${upload.bytes.size}\"",
            contentType = upload.options.contentType,
            lastModified = Instant.EPOCH,
        )
    },
): ImageStorage by delegate, ImageObjectMetadataReader {

/**
 * uploads은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val uploads: MutableList<RecordedUpload>
        get() = delegate.uploads

/**
 * deletes은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deletes: MutableList<ImageObjectKey>
        get() = delegate.deletes

/**
 * downloads은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val downloads: MutableList<ImageObjectKey>
        get() = delegate.downloads

/**
 * metadataReads은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val metadataReads = mutableListOf<ImageObjectKey>()

/**
 * readMetadata은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun readMetadata(key: ImageObjectKey): ImageObjectMetadata {
        if (failOnMetadataKeyPart != null && key.fullKey.contains(failOnMetadataKeyPart)) {
            throw ImageStorageException.TransientException(
                key = key,
                message = "forced metadata failure: ${key.fullKey}",
            )
        }
        val upload = uploads.singleOrNull { it.key == key }
            ?: throw ImageStorageException.NotFoundException(key)
        metadataReads += key
        return metadataFn(key, upload)
    }
}

/**
 * 실제 영속화가 필요 없는 단위 테스트용 no-op [ImagePersistenceService]입니다.
 *
 * [recordJobStart]는 기본적으로 [JobStartResult.NewAsset]을 반환하며 [startResultFn]으로 재정의할 수 있습니다.
 * [findAssetByExternalId]는 기본적으로 `null`을 반환하며 [detailResponseFn]으로 재정의할 수 있습니다.
 */
internal class StubImagePersistenceService(
/**
 * startResultFn은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val startResultFn: (() -> JobStartResult)? = null,
/**
 * detailResponseFn은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val detailResponseFn: ((String) -> ImageAssetDetailResponse?)? = null,
): ImagePersistenceService {

/**
 * events은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val events = mutableListOf<RecordedEvent>()

/**
 * recordJobStart은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun recordJobStart(metadata: AssetMetadataInput): JobStartResult =
        startResultFn?.invoke() ?: JobStartResult.NewAsset(
            assetId = 1L,
            jobId = 1L,
            externalId = Base58.randomString(12),
        )

/**
 * recordJobSuccess은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun recordJobSuccess(identity: JobIdentity, objects: List<ImageObjectInput>) {}

/**
 * recordJobFailure은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun recordJobFailure(identity: JobIdentity, reason: JobFailureReason, durationMs: Long) {}

/**
 * appendEvent은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun appendEvent(
        jobId: Long,
        step: ImageProcessingStep,
        status: ImageProcessingEventStatus,
        message: String,
        payload: Map<String, Any?>,
    ) {
        events += RecordedEvent(step, status, message, payload)
    }

/**
 * findAssetByExternalId은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun findAssetByExternalId(externalId: String): ImageAssetDetailResponse? =
        detailResponseFn?.invoke(externalId)

/**
 * findAssetHistory은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun findAssetHistory(externalId: String): ImageAssetHistoryResponse? = null
}

/**
 * StubDerivativeProcessor 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class StubDerivativeProcessor(
/**
 * keyFactory은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val keyFactory: ImageKeyFactory,
): DerivativeProcessor {

/**
 * process은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun process(bytes: ByteArray, imageId: String): ProcessedImageSet =
        ProcessedImageSet(
            original = OriginalImageInfo(width = 640, height = 480),
            variants = listOf(
                variant(imageId, "thumb-128", 128, 96),
                variant(imageId, "card-320", 320, 240),
                variant(imageId, "detail-1024", 640, 480),
            ),
        )

/**
 * variant은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun variant(
        imageId: String,
        name: String,
        width: Int,
        height: Int,
    ): ProcessedImageVariant =
        ProcessedImageVariant(
            name = name,
            key = keyFactory.variantKey(imageId, name, "webp"),
            bytes = "fake-$name".encodeToByteArray(),
            width = width,
            height = height,
            contentType = "image/webp",
        )
}
