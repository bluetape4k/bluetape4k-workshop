package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.images.spring.storage.ImageStorage
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

/**
 * Orchestrates the image upload → process → store → persist saga.
 *
 * ## Saga phases
 * 1. **T1** `recordJobStart` — deduplication check + new job creation.
 * 2. Image processing and S3 upload (with event emission at each step).
 * 3. **T2** `recordJobSuccess` / **T3** `recordJobFailure` — final saga state.
 *
 * ## Cancellation contract
 * - Happy-path event emission propagates `CancellationException`.
 * - T2/T3 saga writes and their events run in `NonCancellable + IO` so they
 *   complete even if the caller coroutine is cancelled.
 * - Event emission failures are non-fatal: logged at WARN and swallowed.
 */
@Service
class ImageDerivativeWorkflowService(
    private val storage: ImageStorage,
    private val validator: UploadImageValidator,
    private val processor: DerivativeProcessor,
    private val keyFactory: ImageKeyFactory,
    private val urlResolver: PublicImageUrlResolver,
    private val properties: ImageProcessingAdvancedProperties,
    private val meterRegistry: MeterRegistry,
    private val persistenceService: ImagePersistenceService,
) {

    private val requestSemaphore = Semaphore(properties.requestConcurrency)

    suspend fun processUpload(file: MultipartFile): ImageProcessingResponse {
        val sample = Timer.start(meterRegistry)
        var resultTag = "failure"
        try {
            return withTimeout(properties.processingTimeout.toMillis()) {
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

    private suspend fun processUploadInternal(file: MultipartFile): ImageProcessingResponse {
        val started = System.nanoTime()

        // --- Validation ---
        validator.validateDeclaredSize(file.size)
        val bytes = file.bytes
        val uploadOptions = validator.validate(file.contentType, bytes)
        meterRegistry.counter(METRIC_UPLOAD_ACCEPTED, "contentType", uploadOptions.contentType).increment()

        // --- T1: recordJobStart (deduplication + job creation) ---
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

        // AlreadyReady: skip processing, serve cached response from DB.
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
        try {
            // --- Processing ---
            val processed = processor.process(bytes, imageId)
            safeAppendEvent(
                jobId = identity.jobId,
                step = ImageProcessingStep.VIPS_PROCESSING,
                status = ImageProcessingEventStatus.COMPLETED,
                message = "Image processing complete: ${processed.variants.size} variant(s)",
                payload = mapOf("variantCount" to processed.variants.size),
            )

            // --- Upload original ---
            val originalKey = keyFactory.originalKey(imageId, file.originalFilename)
            val originalUpload = storage.upload(originalKey, bytes, uploadOptions)
            uploadedKeys += originalKey
            imageObjects += ImageObjectInput(
                kind = ImageObjectKind.ORIGINAL,
                variantName = null,
                s3Key = originalKey.fullKey,
                publicUrl = urlResolver.resolve(originalKey),
                width = processed.original.width,
                height = processed.original.height,
                byteSize = originalUpload.sizeBytes,
                format = originalUpload.contentType,
            )

            // --- Upload variants ---
            val variants = processed.variants.map { variant ->
                val variantUpload = storage.upload(
                    key = variant.key,
                    bytes = variant.bytes,
                    options = UploadOptions(contentType = variant.contentType),
                )
                uploadedKeys += variant.key
                imageObjects += ImageObjectInput(
                    kind = ImageObjectKind.VARIANT,
                    variantName = variant.name,
                    s3Key = variant.key.fullKey,
                    publicUrl = urlResolver.resolve(variant.key),
                    width = variant.width,
                    height = variant.height,
                    byteSize = variantUpload.sizeBytes,
                    format = variantUpload.contentType,
                )
                meterRegistry.counter(METRIC_VARIANT_GENERATED, "variant", variant.name).increment()
                ImageVariantMetadata(
                    name = variant.name,
                    key = variant.key.fullKey,
                    url = urlResolver.resolve(variant.key),
                    width = variant.width,
                    height = variant.height,
                    contentType = variantUpload.contentType,
                    sizeBytes = variantUpload.sizeBytes,
                )
            }

            safeAppendEvent(
                jobId = identity.jobId,
                step = ImageProcessingStep.S3_UPLOAD,
                status = ImageProcessingEventStatus.COMPLETED,
                message = "S3 upload complete: ${uploadedKeys.size} object(s)",
                payload = mapOf("objectCount" to uploadedKeys.size),
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
                    contentType = originalUpload.contentType,
                    sizeBytes = originalUpload.sizeBytes,
                ),
                thumbnailUrl = primary.url,
                variants = variants,
                durationMillis = durationMs,
            )
        } catch (e: CancellationException) {
            val durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
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
            val durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
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

    /** Best-effort event emission on the happy path. Swallows non-cancellation exceptions. */
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
     * Best-effort event emission inside a [NonCancellable] block (T2/T3 cleanup paths).
     *
     * Must be called from within `withContext(NonCancellable + ...)`.
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

    private fun recordFailure(stage: String) {
        meterRegistry.counter(METRIC_PROCESSING_FAILURES, "stage", stage).increment()
    }

    private fun computeChecksum(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object : KLogging() {
        const val METRIC_UPLOAD_ACCEPTED = "workshop.images.upload.accepted"
        const val METRIC_PROCESSING_DURATION = "workshop.images.processing.duration"
        const val METRIC_PROCESSING_FAILURES = "workshop.images.processing.failures"
        const val METRIC_VARIANT_GENERATED = "workshop.images.variant.generated"
    }
}
