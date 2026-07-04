package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.ImageStorageException
import io.bluetape4k.images.spring.ImageUploadResult
import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties
import io.bluetape4k.images.spring.storage.ImageStorage
import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationDecision
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationRequest
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationResult
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

internal fun testProperties(
    moderationTimeout: Duration = Duration.ofSeconds(5),
    processingTimeout: Duration = Duration.ofSeconds(5),
    requestConcurrency: Int = 4,
    moderationConcurrency: Int = 2,
    maxInputBytes: Long = 1024 * 1024,
): ProfileImageModerationProperties = ProfileImageModerationProperties(
    moderationTimeout = moderationTimeout,
    processingTimeout = processingTimeout,
    requestConcurrency = requestConcurrency,
    moderationConcurrency = moderationConcurrency,
    maxInputBytes = maxInputBytes,
    decisionDelay = Duration.ZERO,
)

internal class ProfileImageServiceFixture(
    val properties: ProfileImageModerationProperties = testProperties(),
    val storage: RecordingProfileImageStorage = RecordingProfileImageStorage(),
    val provider: ControllableModerationProvider = ControllableModerationProvider(),
    uploadIds: List<String> = listOf("upload-a", "upload-b", "upload-c"),
) {
    val repository = ProfileImageRepository()
    val meterRegistry = SimpleMeterRegistry()
    private val metrics = ProfileImageMetrics(meterRegistry)
    private val keyFactory = ProfileImageKeyFactory()
    private val idIndex = AtomicInteger(0)
    val runner = ProfileImageModerationRunner(provider, repository, properties, metrics)
    val service = ProfileImageService(
        storage = storage,
        validator = UploadImageValidator(properties),
        processor = ProfileImageProcessor(properties),
        keyFactory = keyFactory,
        urlResolver = ProfileImageUrlResolver(properties, ImageStorageProperties()),
        repository = repository,
        runner = runner,
        properties = properties,
        metrics = metrics,
        uploadIdGenerator = { uploadIds[idIndex.getAndIncrement().coerceAtMost(uploadIds.lastIndex)] },
    )

    fun sampleJpeg(width: Int = 32, height: Int = 24): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0x33, 0x66, 0x99)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }
}

internal data class RecordedProfileUpload(
    val key: ImageObjectKey,
    val bytes: ByteArray,
    val options: UploadOptions,
)

internal class RecordingProfileImageStorage(
    private val failOnKeyPart: String? = null,
) : ImageStorage {
    val uploads = mutableListOf<RecordedProfileUpload>()
    val deletes = mutableListOf<ImageObjectKey>()
    val objects = linkedMapOf<ImageObjectKey, ByteArray>()

    override suspend fun upload(key: ImageObjectKey, bytes: ByteArray, options: UploadOptions): ImageUploadResult {
        if (failOnKeyPart != null && key.fullKey.contains(failOnKeyPart)) {
            throw ImageStorageException.TransientException(key = key, message = "forced failure: ${key.fullKey}")
        }
        uploads += RecordedProfileUpload(key, bytes, options)
        objects[key] = bytes
        return ImageUploadResult(key, bytes.size.toString(), bytes.size.toLong(), options.contentType, Instant.EPOCH)
    }

    override suspend fun upload(key: ImageObjectKey, source: Path, options: UploadOptions): ImageUploadResult =
        upload(key, source.toFile().readBytes(), options)

    override suspend fun download(key: ImageObjectKey): ByteArray = objects[key] ?: ByteArray(0)

    override suspend fun download(key: ImageObjectKey, destination: Path) {
        destination.toFile().writeBytes(download(key))
    }

    override suspend fun delete(key: ImageObjectKey) {
        deletes += key
        objects.remove(key)
    }

    override suspend fun exists(key: ImageObjectKey): Boolean = key in objects

    override fun list(prefix: ImageObjectKey): Flow<ImageObjectKey> = emptyFlow()
}

internal class ControllableModerationProvider : ImageModerationProvider {
    val requests = mutableListOf<ModerationRequest>()
    private val completions = ArrayDeque<CompletableDeferred<ModerationResult>>()

    override suspend fun moderate(request: ModerationRequest): ModerationResult {
        requests += request
        val deferred = CompletableDeferred<ModerationResult>()
        completions += deferred
        return deferred.await()
    }

    suspend fun complete(decision: ModerationDecision, reason: String = decision.name.lowercase()) {
        completions.removeFirst().complete(ModerationResult(decision, reason))
    }

    fun fail(error: Throwable) {
        completions.removeFirst().completeExceptionally(error)
    }
}
