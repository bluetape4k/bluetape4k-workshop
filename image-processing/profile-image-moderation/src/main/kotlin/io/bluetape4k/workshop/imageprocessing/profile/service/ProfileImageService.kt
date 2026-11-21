package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.codec.Base58
import io.bluetape4k.images.spring.ImageObjectKey
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
import kotlin.time.toKotlinDuration

@Service
/**
 * 검증, 저장, 대기 preview 생성, 검수 예약을 조율합니다.
 *
 * 업로드는 원본을 private key에 저장하고 pending/approved privacy-safe
 * derivative를 만든 다음 `PENDING_MODERATION`을 반환합니다. moderation 결과는
 * [ProfileImageRepository]의 `userId + uploadId` compare-and-set 경계를 통해서만
 * 현재 상태를 바꿉니다. 오류나 취소가 발생하면 이미 저장한 key를
 * [NonCancellable] cleanup으로 정리합니다.
 * @property storage 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property validator 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property processor 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property keyFactory 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property urlResolver 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property repository 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property runner 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property properties 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property uploadIdGenerator 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class ProfileImageService(
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
    private val processor: ProfileImageProcessor,
    /**
     * `keyFactory`은 이미지 업로드·파생 처리에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val keyFactory: ProfileImageKeyFactory,
    /**
     * `urlResolver`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val urlResolver: ProfileImageUrlResolver,
    /**
     * `repository`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: ProfileImageRepository,
    /**
     * `runner`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val runner: ProfileImageModerationRunner,
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ProfileImageModerationProperties,
    /**
     * `metrics`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: ProfileImageMetrics,
    /**
     * `uploadIdGenerator`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val uploadIdGenerator: () -> String = { Base58.randomString(16) },
) {
    private companion object: KLogging()

    /**
     * `requestSemaphore`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val requestSemaphore = Semaphore(properties.requestConcurrency)

    /**
     * 사용자의 새 프로필 이미지 upload를 접수합니다.
     *
     * 성공은 moderation 완료가 아니라 `202 Accepted`에 해당하는
     * `PENDING_MODERATION` 상태입니다. `effectiveUrl`은 blurred derivative이고
     * private original은 반환하지 않습니다. 잘못된 MIME/magic byte나 처리
     * timeout은 storage에 남은 객체를 정리한 뒤 예외로 전달됩니다.
     * ```kotlin
     * val view = profileImageService.upload("alice", multipartFile)
     * check(view.status == ProfileImageStatus.PENDING_MODERATION)
     * ```
     */
    suspend fun upload(userId: String, file: MultipartFile): ProfileImageView =
        requestSemaphore.withPermit {
            keyFactory.validateUserId(userId)
            /**
             * `uploaded`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val uploaded = mutableListOf<ImageObjectKey>()

            try {
                withTimeout(properties.processingTimeout.toKotlinDuration()) {
                    extractProfileImageView(file, userId, uploaded)
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

    /**
     * extractProfileImageView은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록
     * 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param file 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     * @param userId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param uploaded 업무 처리에 필요한 uploaded 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private suspend fun extractProfileImageView(
        file: MultipartFile,
        userId: String,
        uploaded: MutableList<ImageObjectKey>,
    ): ProfileImageView {
        validator.validateDeclaredSize(file.size)
        val bytes = file.bytes
        val uploadOptions = validator.validate(file.contentType, bytes)
        val uploadId = uploadIdGenerator()
        val processed = processor.processPrivacySafe(bytes, sourceId = uploadId)
        val keys = keyFactory.keys(userId, uploadId, file.originalFilename)

        storage.upload(keys.original, bytes, uploadOptions)
        uploaded += keys.original
        storage.upload(
            keys.pending,
            processed.pendingBytes,
            UploadOptions(contentType = processed.contentType)
        )
        uploaded += keys.pending
        storage.upload(
            keys.approved,
            processed.approvedBytes,
            UploadOptions(contentType = processed.contentType)
        )
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
        return state.toView()
    }

    /**
     * 현재 사용자 프로필 이미지 상태를 조회합니다.
     *
     * 업로드가 없으면 `NO_IMAGE`와 안정적인 기본 URL을 반환합니다. 상태가
     * `REJECTED` 또는 `MODERATION_FAILED`여도 [ProfileImageState.toView]가
     * 기본 URL을 선택하므로 호출자는 private storage를 직접 알 필요가 없습니다.
     * ```bash
     * curl http://localhost:8080/api/users/alice/profile-image
     * ```
     */
    fun find(userId: String): ProfileImageView {
        keyFactory.validateUserId(userId)

        return repository.find(userId)?.toView()
            ?: ProfileImageView(
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

    /**
     * cleanupUploaded은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param keys 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param original 업무 처리에 필요한 original 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private suspend fun cleanupUploaded(
        keys: List<ImageObjectKey>,
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
}
