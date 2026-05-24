package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.ImageStorageException
import io.bluetape4k.images.spring.ImageUploadResult
import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.images.spring.storage.ImageStorage
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageVariantProperties
import io.bluetape4k.workshop.imageprocessing.advanced.model.OriginalImageInfo
import io.bluetape4k.workshop.imageprocessing.advanced.model.ProcessedImageSet
import io.bluetape4k.workshop.imageprocessing.advanced.model.ProcessedImageVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

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

internal val SAMPLE_JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)

internal data class RecordedUpload(
    val key: ImageObjectKey,
    val bytes: ByteArray,
    val options: UploadOptions,
)

internal class RecordingImageStorage(
    private val failOnKeyPart: String? = null,
) : ImageStorage {

    val uploads = mutableListOf<RecordedUpload>()
    val deletes = mutableListOf<ImageObjectKey>()

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

    override suspend fun upload(
        key: ImageObjectKey,
        source: Path,
        options: UploadOptions,
    ): ImageUploadResult =
        upload(key, source.toFile().readBytes(), options)

    override suspend fun download(key: ImageObjectKey): ByteArray = ByteArray(0)

    override suspend fun download(key: ImageObjectKey, destination: Path) = Unit

    override suspend fun delete(key: ImageObjectKey) {
        deletes += key
    }

    override suspend fun exists(key: ImageObjectKey): Boolean = uploads.any { it.key == key }

    override fun list(prefix: ImageObjectKey): Flow<ImageObjectKey> = emptyFlow()
}

internal class StubDerivativeProcessor(
    private val keyFactory: ImageKeyFactory,
) : DerivativeProcessor {

    override suspend fun process(bytes: ByteArray, imageId: String): ProcessedImageSet =
        ProcessedImageSet(
            original = OriginalImageInfo(width = 640, height = 480),
            variants = listOf(
                variant(imageId, "thumb-128", 128, 96),
                variant(imageId, "card-320", 320, 240),
                variant(imageId, "detail-1024", 640, 480),
            ),
        )

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
