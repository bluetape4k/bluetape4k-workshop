package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.codec.Base58
import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.images.spring.storage.ImageStorage
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationRequest
import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageState
import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageStatus
import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.Instant

@Service
/**
 * Coordinates validation, storage, pending preview generation, and moderation scheduling.
 */
class ProfileImageService(
    private val storage: ImageStorage,
    private val validator: UploadImageValidator,
    private val processor: ProfileImageProcessor,
    private val keyFactory: ProfileImageKeyFactory,
    private val urlResolver: ProfileImageUrlResolver,
    private val repository: ProfileImageRepository,
    private val runner: ProfileImageModerationRunner,
    private val properties: ProfileImageModerationProperties,
    private val metrics: ProfileImageMetrics,
    private val uploadIdGenerator: () -> String = { Base58.randomString(16) },
) {

    private val requestSemaphore = Semaphore(properties.requestConcurrency)

    suspend fun upload(userId: String, file: MultipartFile): ProfileImageView = requestSemaphore.withPermit {
        keyFactory.validateUserId(userId)
        val uploaded = mutableListOf<io.bluetape4k.images.spring.ImageObjectKey>()
        try {
            withTimeout(properties.processingTimeout.toMillis()) {
                validator.validateDeclaredSize(file.size)
                val bytes = file.bytes
                val uploadOptions = validator.validate(file.contentType, bytes)
                val processed = processor.process(bytes)
                val uploadId = uploadIdGenerator()
                val keys = keyFactory.keys(userId, uploadId, file.originalFilename)

                storage.upload(keys.original, bytes, uploadOptions)
                uploaded += keys.original
                storage.upload(keys.pending, processed.pendingBytes, UploadOptions(contentType = processed.contentType))
                uploaded += keys.pending
                storage.upload(keys.approved, processed.approvedBytes, UploadOptions(contentType = processed.contentType))
                uploaded += keys.approved

                val state = repository.savePending(
                    ProfileImageState(
                        userId = userId,
                        uploadId = uploadId,
                        status = ProfileImageStatus.PENDING_MODERATION,
                        keys = keys,
                        pendingUrl = urlResolver.resolve(keys.pending),
                        approvedUrl = urlResolver.resolve(keys.approved),
                        defaultImageUrl = properties.defaultImageUrl,
                        originalFilename = file.originalFilename,
                        updatedAt = Instant.now(),
                    ),
                )
                metrics.uploadAccepted(uploadOptions.contentType)
                runner.schedule(
                    ModerationRequest(
                        userId = userId,
                        uploadId = uploadId,
                        originalFilename = file.originalFilename,
                        originalKey = keys.original,
                    ),
                )
                state.toView()
            }
        } catch (e: TimeoutCancellationException) {
            metrics.uploadRejected("timeout")
            cleanupUploaded(uploaded, e)
            throw e
        } catch (e: CancellationException) {
            cleanupUploaded(uploaded, e)
            throw e
        } catch (e: IllegalArgumentException) {
            metrics.uploadRejected("validation")
            cleanupUploaded(uploaded, e)
            throw e
        } catch (e: Exception) {
            metrics.uploadRejected("failure")
            cleanupUploaded(uploaded, e)
            throw e
        }
    }

    fun find(userId: String): ProfileImageView {
        keyFactory.validateUserId(userId)
        return repository.find(userId)?.toView() ?: ProfileImageView(
            userId = userId,
            status = ProfileImageStatus.NO_IMAGE,
            uploadId = null,
            effectiveUrl = properties.defaultImageUrl,
            pendingUrl = null,
            approvedUrl = null,
            defaultImageUrl = properties.defaultImageUrl,
            reason = "no profile image uploaded",
            updatedAt = Instant.EPOCH,
        )
    }

    private suspend fun cleanupUploaded(
        keys: List<io.bluetape4k.images.spring.ImageObjectKey>,
        original: Throwable,
    ) {
        if (keys.isEmpty()) return
        withContext(NonCancellable) {
            keys.asReversed().forEach { key ->
                try {
                    storage.delete(key)
                    metrics.cleanup("deleted")
                } catch (cleanupFailure: Exception) {
                    metrics.cleanup("failed")
                    log.warn(cleanupFailure) { "Failed to cleanup profile image object: ${key.fullKey}" }
                    original.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    companion object : KLogging()
}
