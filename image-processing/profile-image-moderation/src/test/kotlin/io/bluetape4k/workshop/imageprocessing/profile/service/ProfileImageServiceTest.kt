package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContentEqual
import io.bluetape4k.assertions.shouldEndWith
import io.bluetape4k.images.analysis.ImageMetadataReadOptions
import io.bluetape4k.images.analysis.ImageMetadataReadResult
import io.bluetape4k.images.analysis.readImageMetadataReportStrict
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationDecision
import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageStatus
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 프로필 이미지 상태 머신과 storage 실패 복구를 검증합니다.
 *
 * upload 직후 pending blurred URL, 승인/반려/timeout/provider failure의 기본
 * 이미지 fallback, 최신 upload 우선 CAS, invalid input의 pre-storage 거부,
 * partial object cleanup, private original과 privacy-safe derivative를
 * 확인합니다. 테스트의 목표는 외부 moderation model 정확도가 아니라 제품이
 * 모든 상태에서 안전한 effective URL을 선택하는지입니다.
 */
class ProfileImageServiceTest {

    companion object: KLogging()

    @Test
/**
 * upload_returns_pending_before_moderation_completes은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun upload_returns_pending_before_moderation_completes() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "safe.jpg", "image/jpeg", fixture.sampleJpeg())

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = fixture.service.upload("user-1", file)

        log.debug { "response=$response" }
        response.status shouldBeEqualTo ProfileImageStatus.PENDING_MODERATION
        response.effectiveUrl shouldBeEqualTo response.pendingUrl
        response.effectiveUrl shouldEndWith "/pending/blurred.jpg"
        fixture.storage.uploads.map { it.key.fullKey } shouldBeEqualTo listOf(
            "profile-images/user-1/upload-a/private/original/safe.jpg",
            "profile-images/user-1/upload-a/pending/blurred.jpg",
            "profile-images/user-1/upload-a/public/approved.jpg",
        )
    }

    @Test
/**
 * upload_keeps_private_original_bytes_and_publishes_verified_derivatives은 이미지 처리 테스트 fixture의 업무
 * 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun upload_keeps_private_original_bytes_and_publishes_verified_derivatives() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
/**
 * original은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val original = fixture.sampleJpeg()

        fixture.service.upload(
            "user-1",
            MockMultipartFile("file", "safe.jpg", "image/jpeg", original)
        )

        fixture.storage.uploads.first().bytes shouldContentEqual original
        fixture.storage.uploads.drop(1)
            .forEach { upload ->
/**
 * report은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val report = readImageMetadataReportStrict(
                    upload.bytes,
                    ImageMetadataReadOptions(stripSensitiveMetadata = false),
                )
                report.shouldBeInstanceOf<ImageMetadataReadResult.Success>()
                log.debug { "report=${report.report}" }
                report.report.containsGps.shouldBeFalse()
                report.report.containsExif.shouldBeFalse()
                report.report.containsXmp.shouldBeFalse()
                report.report.containsIptc.shouldBeFalse()
                report.report.containsIccProfile.shouldBeFalse()
            }
    }

    @Test
/**
 * approved_completion_switches_effective_url은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun approved_completion_switches_effective_url() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
        fixture.service.upload(
            "user-1",
            MockMultipartFile("file", "safe.jpg", "image/jpeg", fixture.sampleJpeg())
        )
        awaitModerationRequest(fixture)

        fixture.provider.complete(ModerationDecision.APPROVED, "ok")
        awaitStatus(fixture, ProfileImageStatus.APPROVED)

/**
 * view은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val view = fixture.service.find("user-1")

        log.debug { "view=$view" }
        view.status shouldBeEqualTo ProfileImageStatus.APPROVED
        view.effectiveUrl shouldEndWith "/public/approved.jpg"
        view.pendingUrl.shouldBeNull()
    }

    @Test
/**
 * rejected_completion_switches_to_default_url은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun rejected_completion_switches_to_default_url() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
        fixture.service.upload("user-1", MockMultipartFile("file", "reject.jpg", "image/jpeg", fixture.sampleJpeg()))
        awaitModerationRequest(fixture)

        fixture.provider.complete(ModerationDecision.REJECTED, "unsafe")
        awaitStatus(fixture, ProfileImageStatus.REJECTED)
/**
 * view은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val view = fixture.service.find("user-1")

        log.debug { "view=$view" }
        view.status shouldBeEqualTo ProfileImageStatus.REJECTED
        view.effectiveUrl shouldBeEqualTo view.defaultImageUrl
        view.reason shouldBeEqualTo "unsafe"
    }

    @Test
/**
 * stale_completion_from_older_upload_is_ignored은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun stale_completion_from_older_upload_is_ignored() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
        fixture.service.upload("user-1", MockMultipartFile("file", "first.jpg", "image/jpeg", fixture.sampleJpeg()))
        awaitModerationRequest(fixture, 1)
        fixture.service.upload("user-1", MockMultipartFile("file", "second.jpg", "image/jpeg", fixture.sampleJpeg()))
        awaitModerationRequest(fixture, 2)

        fixture.provider.complete(ModerationDecision.APPROVED, "older ok")
        delay(50.milliseconds)
        fixture.service.find("user-1").status shouldBeEqualTo ProfileImageStatus.PENDING_MODERATION
        fixture.service.find("user-1").uploadId shouldBeEqualTo "upload-b"

        fixture.provider.complete(ModerationDecision.REJECTED, "second rejected")
        awaitStatus(fixture, ProfileImageStatus.REJECTED)
        fixture.service.find("user-1").reason shouldBeEqualTo "second rejected"
    }

    @Test
/**
 * moderation_failure_becomes_failed_state은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun moderation_failure_becomes_failed_state() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
        fixture.service.upload("user-1", MockMultipartFile("file", "safe.jpg", "image/jpeg", fixture.sampleJpeg()))
        awaitModerationRequest(fixture)

        fixture.provider.fail(IllegalStateException("provider down"))
        awaitStatus(fixture, ProfileImageStatus.MODERATION_FAILED)
/**
 * view은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val view = fixture.service.find("user-1")

        log.debug { "view=$view" }
        view.effectiveUrl shouldBeEqualTo view.defaultImageUrl
        view.reason shouldBeEqualTo "provider down"
    }

    @Test
/**
 * invalid_upload_rejects_before_storage은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun invalid_upload_rejects_before_storage() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "note.txt", "text/plain", "plain".encodeToByteArray())

        assertFailsWith<IllegalArgumentException> {
            fixture.service.upload("user-1", file)
        }
        fixture.storage.uploads.size shouldBeEqualTo 0
    }

    @Test
/**
 * derivative_storage_failure_cleans_up_partial_uploads은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는
 * 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun derivative_storage_failure_cleans_up_partial_uploads() = runSuspendIO {
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val storage = RecordingProfileImageStorage(failOnKeyPart = "pending")
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture(storage = storage)
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "safe.jpg", "image/jpeg", fixture.sampleJpeg())

        assertFailsWith<Exception> {
            fixture.service.upload("user-1", file)
        }

        log.debug { "storage=$storage" }
        storage.uploads.map { it.key.fullKey } shouldBeEqualTo listOf("profile-images/user-1/upload-a/private/original/safe.jpg")
        storage.deletes.map { it.fullKey } shouldBeEqualTo listOf("profile-images/user-1/upload-a/private/original/safe.jpg")
        fixture.service.find("user-1").status shouldBeEqualTo ProfileImageStatus.NO_IMAGE
    }

    @Test
/**
 * no_image_returns_default_view은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun no_image_returns_default_view() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()

/**
 * view은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val view = fixture.service.find("missing")

        log.debug { "view=$view" }
        view.status shouldBeEqualTo ProfileImageStatus.NO_IMAGE
        view.effectiveUrl shouldBeEqualTo view.defaultImageUrl
        view.uploadId.shouldBeNull()
    }

    @Test
/**
 * private_original_keys_are_not_publicly_resolvable은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun private_original_keys_are_not_publicly_resolvable() {
        val fixture = ProfileImageServiceFixture()
        val resolver = ProfileImageUrlResolver(
            fixture.properties,
            io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties()
        )
        val keys = ProfileImageKeyFactory().keys("user-1", "upload-a", "safe.jpg")

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(keys.original)
        }
    }

    @Test
/**
 * moderation_timeout_becomes_failed_state은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun moderation_timeout_becomes_failed_state() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture(properties = testProperties(moderationTimeout = Duration.ofMillis(30)))
        fixture.service.upload(
            "user-1",
            MockMultipartFile("file", "safe.jpg", "image/jpeg", fixture.sampleJpeg())
        )

        awaitStatus(fixture, ProfileImageStatus.MODERATION_FAILED, attempts = 20)

        fixture.service.find("user-1").effectiveUrl shouldBeEqualTo fixture.service.find("user-1").defaultImageUrl
    }

/**
 * awaitModerationRequest은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private suspend fun awaitModerationRequest(fixture: ProfileImageServiceFixture, count: Int = 1) {
        repeat(20) {
            if (fixture.provider.requests.size >= count) return
            delay(25.milliseconds)
        }
        fixture.provider.requests.size shouldBeEqualTo count
    }

/**
 * awaitStatus은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private suspend fun awaitStatus(
        fixture: ProfileImageServiceFixture,
        status: ProfileImageStatus,
        attempts: Int = 20,
    ) {
        repeat(attempts) {
            if (fixture.service.find("user-1").status == status) return
            delay(25.milliseconds)
        }
        fixture.service.find("user-1").status shouldBeEqualTo status
    }
}
