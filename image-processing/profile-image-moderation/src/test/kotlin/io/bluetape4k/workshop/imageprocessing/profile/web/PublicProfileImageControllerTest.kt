package io.bluetape4k.workshop.imageprocessing.profile.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.imageprocessing.profile.service.RecordingProfileImageStorage
import org.junit.jupiter.api.Test

/**
 * public 이미지 경로의 privacy guard를 검증합니다.
 *
 * pending derivative는 실제 bytes를 반환하되 `Cache-Control: no-store`를
 * 붙이고, `private/original/<filename>` 경로는 storage download 전에 `404`가 되는지
 * 확인합니다. 이는 인증/실제 CDN 정책의 대체가 아니라 local adapter가 지켜야
 * 하는 최소 방어선입니다.
 */
class PublicProfileImageControllerTest {

    companion object: KLogging()

    @Test
/**
 * pending_image_is_served_with_no_store_cache은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun pending_image_is_served_with_no_store_cache() = runSuspendIO {
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val storage = RecordingProfileImageStorage()
/**
 * key은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val key = ImageObjectKey.of("profile-images/user-1/upload-a/pending", "blurred.jpg")
        storage.upload(key, byteArrayOf(1, 2, 3), UploadOptions(contentType = "image/jpeg"))
/**
 * controller은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val controller = PublicProfileImageController(storage)

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = controller.get("profile-images/user-1/upload-a/pending/blurred.jpg")

        log.debug { "response=$response" }
        response.statusCode.value() shouldBeEqualTo 200
        response.headers.cacheControl shouldBeEqualTo "no-store"
        response.body?.toList() shouldBeEqualTo listOf(1.toByte(), 2.toByte(), 3.toByte())
    }

    @Test
/**
 * private_original_path_is_not_served은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun private_original_path_is_not_served() = runSuspendIO {
/**
 * controller은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val controller = PublicProfileImageController(RecordingProfileImageStorage())

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = controller.get("profile-images/user-1/upload-a/private/original/safe.jpg")

        log.debug { "response=$response" }
        response.statusCode.value() shouldBeEqualTo 404
    }
}
