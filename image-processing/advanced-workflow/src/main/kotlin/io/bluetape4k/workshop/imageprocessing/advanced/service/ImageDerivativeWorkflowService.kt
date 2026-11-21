package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.ImageUploadResult
import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.images.spring.storage.ImageObjectMetadataReader
import io.bluetape4k.images.spring.storage.ImageStorage
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties
import io.bluetape4k.workshop.imageprocessing.advanced.model.AssetMetadataInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectDTO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectKind
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageVariantMetadata
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobFailureReason
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobIdentity
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.model.OriginalImageMetadata
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.ImagePersistenceService
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration

/**
 * 이미지 업로드 → 처리 → 저장 → 영속화 사가를 조율합니다.
 *
 * ## 사가 단계
 * 1. **T1** `recordJobStart` — 중복 제거 검사 + 새 job 생성.
 * 2. 이미지 처리와 S3 업로드(각 단계에서 이벤트 발행).
 * 3. **T2** `recordJobSuccess` / **T3** `recordJobFailure` — 최종 사가 상태.
 *
 * ## 취소 계약
 * - 정상 경로 이벤트 발행은 `CancellationException`을 전파합니다.
 * - T2/T3 사가 쓰기와 해당 이벤트는 `NonCancellable + IO`에서 실행하므로
 *   호출자 코루틴이 취소되어도 완료됩니다.
 * - 이벤트 발행 실패는 치명적이지 않습니다. WARN으로 기록하고 삼킵니다.
 */
@Service
class ImageDerivativeWorkflowService(
    /**
     * `storage`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val storage: ImageStorage,
    /**
     * `validator`은 이미지 업로드·파생 처리에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    private val validator: UploadImageValidator,
    /**
     * `processor`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val processor: DerivativeProcessor,
    /**
     * `keyFactory`은 이미지 업로드·파생 처리에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val keyFactory: ImageKeyFactory,
    /**
     * `urlResolver`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val urlResolver: PublicImageUrlResolver,
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ImageProcessingAdvancedProperties,
    /**
     * `meterRegistry`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val meterRegistry: MeterRegistry,
    /**
     * `persistenceService`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val persistenceService: ImagePersistenceService,
) {

    companion object: KLogging() {
        /**
         * `METRIC_UPLOAD_ACCEPTED`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        const val METRIC_UPLOAD_ACCEPTED = "workshop.images.upload.accepted"
        /**
         * `METRIC_PROCESSING_DURATION`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val METRIC_PROCESSING_DURATION = "workshop.images.processing.duration"
        /**
         * `METRIC_PROCESSING_FAILURES`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        const val METRIC_PROCESSING_FAILURES = "workshop.images.processing.failures"
        /**
         * `METRIC_VARIANT_GENERATED`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val METRIC_VARIANT_GENERATED = "workshop.images.variant.generated"
        /**
         * `METRIC_METADATA_READ`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val METRIC_METADATA_READ = "workshop.images.storage.metadata.read"
        /**
         * `METRIC_METADATA_UNAVAILABLE`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val METRIC_METADATA_UNAVAILABLE = "workshop.images.storage.metadata.unavailable"
    }

    /**
     * `requestSemaphore`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val requestSemaphore = Semaphore(properties.requestConcurrency)
    /**
     * `metadataReader`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val metadataReader = storage as? ImageObjectMetadataReader

    /**
     * processUpload은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param file 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    suspend fun processUpload(file: MultipartFile): ImageProcessingResponse {
        val sample = Timer.start(meterRegistry)
        var resultTag = "failure"
        try {
            return withTimeout(properties.processingTimeout.toKotlinDuration()) {
                requestSemaphore.withPermit {
                    val response = processUploadInternal(file)
                    resultTag = "success"
                    response
                }
            }
        } catch (e: TimeoutCancellationException) {
            resultTag = "timeout"
            recordFailure("timeout")
            throw e
        } catch (e: CancellationException) {
            resultTag = "cancelled"
            recordFailure("cancelled")
            throw e
        } catch (e: IllegalArgumentException) {
            resultTag = "validation"
            recordFailure("validation")
            throw e
        } catch (e: Exception) {
            resultTag = "failure"
            recordFailure("unknown")
            throw e
        } finally {
            sample.stop(
                Timer.builder(METRIC_PROCESSING_DURATION)
                    .tag("result", resultTag)
                    .register(meterRegistry)
            )
        }
    }

    /**
     * processUploadInternal은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param file 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private suspend fun processUploadInternal(file: MultipartFile): ImageProcessingResponse {
        val started = System.nanoTime()

        // --- 검증 ---
        validator.validateDeclaredSize(file.size)
        val bytes = file.bytes
        val uploadOptions = validator.validate(file.contentType, bytes)
        meterRegistry.counter(METRIC_UPLOAD_ACCEPTED, "contentType", uploadOptions.contentType).increment()

        // --- T1: recordJobStart(중복 제거 + job 생성) ---
        val checksum = computeChecksum(bytes)
        val metadata = AssetMetadataInput(
            checksum = checksum,
            originalFilename = file.originalFilename,
            contentType = uploadOptions.contentType,
            byteSize = bytes.size.toLong(),
            dimensions = null,
        )
        val jobStartResult = withContext(Dispatchers.IO) {
            persistenceService.recordJobStart(metadata)
        }

        // AlreadyReady: 처리를 건너뛰고 DB의 캐시 응답을 제공합니다.
        if (jobStartResult is JobStartResult.AlreadyReady) {
            val cached = withContext(Dispatchers.IO) {
                persistenceService.findAssetByExternalId(jobStartResult.externalId)
            }
            if (cached != null) {
                return buildCachedResponse(cached.imageId, cached.original, cached.variants, started)
            }
        }

        val identity = JobIdentity(jobStartResult.jobId, jobStartResult.assetId)
        val imageId = jobStartResult.externalId

        safeAppendEvent(
            jobId = identity.jobId,
            step = ImageProcessingStep.VALIDATION,
            status = ImageProcessingEventStatus.COMPLETED,
            message = "Upload validation passed",
        )

        val uploadedKeys = mutableListOf<ImageObjectKey>()
        val imageObjects = mutableListOf<ImageObjectInput>()
        val metadataSnapshots = mutableListOf<ResolvedStorageMetadata>()
        try {
            // --- 처리 ---
            val processed = processor.process(bytes, imageId)
            safeAppendEvent(
                jobId = identity.jobId,
                step = ImageProcessingStep.VIPS_PROCESSING,
                status = ImageProcessingEventStatus.COMPLETED,
                message = "Image processing complete: ${processed.variants.size} variant(s)",
                payload = mapOf("variantCount" to processed.variants.size),
            )

            // --- 원본 업로드 ---
            val originalKey = keyFactory.originalKey(imageId, file.originalFilename)
            val originalUpload = storage.upload(originalKey, bytes, uploadOptions)
            uploadedKeys += originalKey
            val originalMetadata = resolveStorageMetadata(originalKey, originalUpload)
            metadataSnapshots += originalMetadata
            imageObjects += ImageObjectInput(
                kind = ImageObjectKind.ORIGINAL,
                variantName = null,
                s3Key = originalKey.fullKey,
                publicUrl = urlResolver.resolve(originalKey),
                width = processed.original.width,
                height = processed.original.height,
                byteSize = originalMetadata.sizeBytes,
                format = originalMetadata.contentType,
            )

            // --- 변형 업로드 ---
            val variants = processed.variants.map { variant ->
                val variantUpload = storage.upload(
                    key = variant.key,
                    bytes = variant.bytes,
                    options = UploadOptions(contentType = variant.contentType),
                )
                uploadedKeys += variant.key
                val variantMetadata = resolveStorageMetadata(variant.key, variantUpload)
                metadataSnapshots += variantMetadata
                imageObjects += ImageObjectInput(
                    kind = ImageObjectKind.VARIANT,
                    variantName = variant.name,
                    s3Key = variant.key.fullKey,
                    publicUrl = urlResolver.resolve(variant.key),
                    width = variant.width,
                    height = variant.height,
                    byteSize = variantMetadata.sizeBytes,
                    format = variantMetadata.contentType,
                )
                meterRegistry.counter(METRIC_VARIANT_GENERATED, "variant", variant.name).increment()
                ImageVariantMetadata(
                    name = variant.name,
                    key = variant.key.fullKey,
                    url = urlResolver.resolve(variant.key),
                    width = variant.width,
                    height = variant.height,
                    contentType = variantMetadata.contentType,
                    sizeBytes = variantMetadata.sizeBytes,
                )
            }

            safeAppendEvent(
                jobId = identity.jobId,
                step = ImageProcessingStep.S3_UPLOAD,
                status = ImageProcessingEventStatus.COMPLETED,
                message = "S3 upload complete: ${uploadedKeys.size} object(s)",
                payload = mapOf(
                    "objectCount" to uploadedKeys.size,
                    "metadataCapability" to (metadataReader != null),
                    "metadata" to metadataSnapshots.map { it.toEventPayload() },
                ),
            )

            // --- T2: recordJobSuccess ---
            val durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
            withContext(NonCancellable + Dispatchers.IO) {
                persistenceService.recordJobSuccess(identity, imageObjects)
                safeAppendEventNonCancellable(
                    jobId = identity.jobId,
                    step = ImageProcessingStep.JOB_COMPLETED,
                    status = ImageProcessingEventStatus.COMPLETED,
                    message = "Job completed in ${durationMs}ms",
                    payload = mapOf("durationMs" to durationMs),
                )
            }

            val primary = variants.singleOrNull { variant ->
                properties.variants.first { it.name == variant.name }.primaryThumbnail
            } ?: variants.first()

            return ImageProcessingResponse(
                imageId = imageId,
                original = OriginalImageMetadata(
                    key = originalKey.fullKey,
                    url = urlResolver.resolve(originalKey),
                    width = processed.original.width,
                    height = processed.original.height,
                    contentType = originalMetadata.contentType,
                    sizeBytes = originalMetadata.sizeBytes,
                ),
                thumbnailUrl = primary.url,
                variants = variants,
                durationMillis = durationMs,
            )
        } catch (e: CancellationException) {
            val durationMs = (System.nanoTime() - started).nanos().toMillis()
            cleanupUploaded(uploadedKeys, e)
            withContext(NonCancellable + Dispatchers.IO) {
                val reason = JobFailureReason("CANCELLED", "Job was cancelled: ${e.message?.take(200) ?: "unknown"}")
                persistenceService.recordJobFailure(identity, reason, durationMs)
                safeAppendEventNonCancellable(
                    jobId = identity.jobId,
                    step = ImageProcessingStep.JOB_FAILED,
                    status = ImageProcessingEventStatus.FAILED,
                    message = "Job cancelled",
                )
            }
            throw e
        } catch (e: Exception) {
            val durationMs = (System.nanoTime() - started).nanos().toMillis()
            cleanupUploaded(uploadedKeys, e)
            withContext(NonCancellable + Dispatchers.IO) {
                val errorCode = when (e) {
                    is IllegalArgumentException -> "VALIDATION_ERROR"
                    else -> "PROCESSING_ERROR"
                }
                val reason = JobFailureReason(errorCode, e.message?.take(500) ?: "Unknown error")
                persistenceService.recordJobFailure(identity, reason, durationMs)
                safeAppendEventNonCancellable(
                    jobId = identity.jobId,
                    step = ImageProcessingStep.JOB_FAILED,
                    status = ImageProcessingEventStatus.FAILED,
                    message = "Job failed: $errorCode",
                    payload = mapOf("errorClass" to e.javaClass.simpleName),
                )
            }
            throw e
        }
    }

    /**
     * cleanupUploaded은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param keys 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param original 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private suspend fun cleanupUploaded(keys: List<ImageObjectKey>, original: Throwable) {
        if (keys.isEmpty()) return
        withContext(NonCancellable) {
            keys.asReversed().forEach { key ->
                try {
                    storage.delete(key)
                } catch (cleanupFailure: CancellationException) {
                    log.warn(cleanupFailure) { "Cleanup was cancelled for uploaded image object: ${key.fullKey}" }
                    original.addSuppressed(cleanupFailure)
                } catch (cleanupFailure: Exception) {
                    log.warn(cleanupFailure) { "Failed to cleanup uploaded image object: ${key.fullKey}" }
                    original.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    /**
     * 업로드 결과와 선택적 metadata snapshot을 하나의 persistence 입력으로 정규화합니다.
     *
     * metadata capability가 없으면 upload 결과를 명시적으로 fallback으로 사용합니다.
     * capability가 있으면 key와 size를 검증한 뒤 snapshot을 우선하며, provider 오류나
     * 교체 경합으로 불일치가 생기면 호출자에게 전파해 saga를 fail-closed로 종료합니다.
     */
    private suspend fun resolveStorageMetadata(
        key: ImageObjectKey,
        upload: ImageUploadResult,
    ): ResolvedStorageMetadata {
        val metadata = metadataReader?.readMetadata(key)
        if (metadata == null) {
            meterRegistry.counter(METRIC_METADATA_UNAVAILABLE).increment()
            return ResolvedStorageMetadata(
                key = key,
                sizeBytes = upload.sizeBytes,
                contentType = upload.contentType,
                etag = null,
                lastModified = null,
                available = false,
            )
        }
        require(metadata.key == key) {
            "Storage metadata key mismatch: expected=${key.fullKey}, actual=${metadata.key.fullKey}"
        }
        require(metadata.sizeBytes == upload.sizeBytes) {
            "Storage metadata size mismatch for ${key.fullKey}: upload=${upload.sizeBytes}, metadata=${metadata.sizeBytes}"
        }
        meterRegistry.counter(METRIC_METADATA_READ).increment()
        return ResolvedStorageMetadata(
            key = key,
            sizeBytes = metadata.sizeBytes,
            contentType = metadata.contentType ?: upload.contentType,
            etag = metadata.etag,
            lastModified = metadata.lastModified,
            available = true,
        )
    }

    /**
     * ResolvedStorageMetadata은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
     * 규칙에 맞춰 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private data class ResolvedStorageMetadata(
        /**
         * `key`은 이미지 업로드·파생 처리에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val key: ImageObjectKey,
        /**
         * `sizeBytes`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val sizeBytes: Long,
        /**
         * `contentType`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val contentType: String,
        /**
         * `etag`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val etag: String?,
        /**
         * `lastModified`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val lastModified: Instant?,
        /**
         * `available`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val available: Boolean,
    ) {
        /**
         * toEventPayload은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        fun toEventPayload(): Map<String, Any?> = mapOf(
            "key" to key.fullKey,
            "available" to available,
            "sizeBytes" to sizeBytes,
            "contentType" to contentType,
            "etag" to etag,
            "lastModified" to lastModified?.toString(),
        )
    }

    /** 정상 경로의 최선 노력 이벤트 발행입니다. 취소가 아닌 예외는 삼킵니다. */
    /** 정상 경로의 최선 노력 이벤트 발행입니다. 취소가 아닌 예외는 삼킵니다. */
    private suspend fun safeAppendEvent(
        jobId: Long,
        step: ImageProcessingStep,
        status: ImageProcessingEventStatus,
        message: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        try {
            withContext(Dispatchers.IO) {
                persistenceService.appendEvent(jobId, step, status, message, payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Failed to append event step=$step" }
        }
    }

    /**
     * [NonCancellable] 블록 안의 최선 노력 이벤트 발행입니다(T2/T3 정리 경로).
     *
     * 반드시 `withContext(NonCancellable + ...)` 내부에서 호출해야 합니다.
     */
    private fun safeAppendEventNonCancellable(
        jobId: Long,
        step: ImageProcessingStep,
        status: ImageProcessingEventStatus,
        message: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        try {
            persistenceService.appendEvent(jobId, step, status, message, payload)
        } catch (e: Exception) {
            log.warn(e) { "Failed to append event step=$step (non-cancellable path)" }
        }
    }

    /**
     * buildCachedResponse은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun buildCachedResponse(
        imageId: String,
        original: ImageObjectDTO?,
        variants: List<ImageObjectDTO>,
        started: Long,
    ): ImageProcessingResponse {
        val originalMeta = original?.let { obj ->
            OriginalImageMetadata(
                key = obj.s3Key,
                url = obj.publicUrl,
                width = obj.width ?: 0,
                height = obj.height ?: 0,
                contentType = obj.format ?: "image/jpeg",
                sizeBytes = obj.byteSize ?: 0L,
            )
        }
        val variantMetas = variants.map { obj ->
            ImageVariantMetadata(
                name = obj.variantName ?: "unknown",
                key = obj.s3Key,
                url = obj.publicUrl,
                width = obj.width ?: 0,
                height = obj.height ?: 0,
                contentType = obj.format ?: "image/jpeg",
                sizeBytes = obj.byteSize ?: 0L,
            )
        }
        val thumbnailUrl = variantMetas.firstOrNull()?.url ?: originalMeta?.url ?: ""
        return ImageProcessingResponse(
            imageId = imageId,
            original = originalMeta ?: OriginalImageMetadata("", "", 0, 0, "image/jpeg", 0L),
            thumbnailUrl = thumbnailUrl,
            variants = variantMetas,
            durationMillis = Duration.ofNanos(System.nanoTime() - started).toMillis(),
            warnings = listOf("Returned from cache — asset already processed"),
        )
    }

    /**
     * recordFailure은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param stage 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun recordFailure(stage: String) {
        meterRegistry.counter(METRIC_PROCESSING_FAILURES, "stage", stage).increment()
    }

    /**
     * computeChecksum은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param bytes 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun computeChecksum(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
