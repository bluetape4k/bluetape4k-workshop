package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.images.spring.storage.ImageStorage
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageVariantMetadata
import io.bluetape4k.workshop.imageprocessing.advanced.model.OriginalImageMetadata
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.Duration
import java.util.UUID

@Service
class ImageDerivativeWorkflowService(
    private val storage: ImageStorage,
    private val validator: UploadImageValidator,
    private val processor: DerivativeProcessor,
    private val keyFactory: ImageKeyFactory,
    private val urlResolver: PublicImageUrlResolver,
    private val properties: ImageProcessingAdvancedProperties,
    private val meterRegistry: MeterRegistry,
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
        val imageId = UUID.randomUUID().toString()
        validator.validateDeclaredSize(file.size)
        val bytes = file.bytes
        val uploadOptions = validator.validate(file.contentType, bytes)
        meterRegistry.counter(METRIC_UPLOAD_ACCEPTED, "contentType", uploadOptions.contentType).increment()

        val uploadedKeys = mutableListOf<ImageObjectKey>()
        val originalKey = keyFactory.originalKey(imageId, file.originalFilename)
        try {
            val processed = processor.process(bytes, imageId)
            val originalUpload = storage.upload(originalKey, bytes, uploadOptions)
            uploadedKeys += originalKey

            val variants = processed.variants.map { variant ->
                val variantUpload = storage.upload(
                    key = variant.key,
                    bytes = variant.bytes,
                    options = UploadOptions(contentType = variant.contentType),
                )
                uploadedKeys += variant.key
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
                durationMillis = Duration.ofNanos(System.nanoTime() - started).toMillis(),
            )
        } catch (e: CancellationException) {
            cleanupUploaded(uploadedKeys, e)
            throw e
        } catch (e: Exception) {
            cleanupUploaded(uploadedKeys, e)
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

    private fun recordFailure(stage: String) {
        meterRegistry.counter(METRIC_PROCESSING_FAILURES, "stage", stage).increment()
    }

    companion object : KLogging() {
        const val METRIC_UPLOAD_ACCEPTED = "workshop.images.upload.accepted"
        const val METRIC_PROCESSING_DURATION = "workshop.images.processing.duration"
        const val METRIC_PROCESSING_FAILURES = "workshop.images.processing.failures"
        const val METRIC_VARIANT_GENERATED = "workshop.images.variant.generated"
    }
}
