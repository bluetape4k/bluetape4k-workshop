package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.ImageStorageException
import io.bluetape4k.images.spring.ImageUploadResult
import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties
import io.bluetape4k.images.spring.storage.ImageStorage
import io.bluetape4k.images.useGraphics
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

/**
 * testProperties은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * ProfileImageServiceFixture 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ProfileImageServiceFixture(
/**
 * properties은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val properties: ProfileImageModerationProperties = testProperties(),
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val storage: RecordingProfileImageStorage = RecordingProfileImageStorage(),
/**
 * provider은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val provider: ControllableModerationProvider = ControllableModerationProvider(),
    uploadIds: List<String> = listOf("upload-a", "upload-b", "upload-c"),
) {
/**
 * repository은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val repository = ProfileImageRepository()
/**
 * meterRegistry은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val meterRegistry = SimpleMeterRegistry()
/**
 * metrics은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val metrics = ProfileImageMetrics(meterRegistry)
/**
 * keyFactory은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val keyFactory = ProfileImageKeyFactory()
/**
 * idIndex은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val idIndex = AtomicInteger(0)
/**
 * runner은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val runner = ProfileImageModerationRunner(provider, repository, properties, metrics)
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * sampleJpeg은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun sampleJpeg(width: Int = 32, height: Int = 24): ByteArray {
/**
 * image은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.useGraphics { graphics ->
            graphics.color = Color(0x33, 0x66, 0x99)
            graphics.fillRect(0, 0, width, height)
        }
        return ByteArrayOutputStream().use { bos ->
            ImageIO.write(image, "jpg", bos)
            bos.toByteArray()
        }
    }
}

/**
 * RecordedProfileUpload 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class RecordedProfileUpload(
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
 * RecordingProfileImageStorage 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class RecordingProfileImageStorage(
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
    val uploads = mutableListOf<RecordedProfileUpload>()
/**
 * deletes은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deletes = mutableListOf<ImageObjectKey>()
/**
 * objects은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val objects = linkedMapOf<ImageObjectKey, ByteArray>()

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
        uploads += RecordedProfileUpload(key, bytes, options)
        objects[key] = bytes

        return ImageUploadResult(
            key,
            bytes.size.toString(),
            bytes.size.toLong(),
            options.contentType,
            Instant.EPOCH
        )
    }

/**
 * upload은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun upload(key: ImageObjectKey, source: Path, options: UploadOptions): ImageUploadResult =
        upload(key, source.toFile().readBytes(), options)

/**
 * download은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun download(key: ImageObjectKey): ByteArray = objects[key] ?: ByteArray(0)

/**
 * download은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun download(key: ImageObjectKey, destination: Path) {
        destination.toFile().writeBytes(download(key))
    }

/**
 * delete은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun delete(key: ImageObjectKey) {
        deletes += key
        objects.remove(key)
    }

/**
 * exists은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun exists(key: ImageObjectKey): Boolean = key in objects

/**
 * list은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun list(prefix: ImageObjectKey): Flow<ImageObjectKey> = emptyFlow()
}

/**
 * ControllableModerationProvider 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ControllableModerationProvider: ImageModerationProvider {
/**
 * requests은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val requests = mutableListOf<ModerationRequest>()
/**
 * completions은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val completions = ArrayDeque<CompletableDeferred<ModerationResult>>()

/**
 * moderate은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun moderate(request: ModerationRequest): ModerationResult {
        requests += request
        val deferred = CompletableDeferred<ModerationResult>()
        completions += deferred
        return deferred.await()
    }

/**
 * complete은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun complete(decision: ModerationDecision, reason: String = decision.name.lowercase()) {
        completions.removeFirst().complete(ModerationResult(decision, reason))
    }

/**
 * fail은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun fail(error: Throwable) {
        completions.removeFirst().completeExceptionally(error)
    }
}
