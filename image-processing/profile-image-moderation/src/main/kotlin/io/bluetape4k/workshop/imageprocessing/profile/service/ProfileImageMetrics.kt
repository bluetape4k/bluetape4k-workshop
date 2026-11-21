package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
/**
 * 검수 예제용 낮은 카디널리티 Micrometer meter를 발행합니다.
 * @property meterRegistry 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class ProfileImageMetrics(
    /**
     * `meterRegistry`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val meterRegistry: MeterRegistry,
) {

    companion object: KLogging() {
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val UPLOAD_ACCEPTED = "workshop.profile.images.upload.accepted"
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val UPLOAD_REJECTED = "workshop.profile.images.upload.rejected"
        /**
         * 이미지 처리의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        const val MODERATION_DURATION = "workshop.profile.images.moderation.duration"
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val TRANSITION = "workshop.profile.images.transition"
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val CLEANUP = "workshop.profile.images.cleanup"
    }

    /**
     * uploadAccepted은 이미지 업로드·검수·파생물 처리 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param contentType 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    fun uploadAccepted(contentType: String) =
        meterRegistry.counter(UPLOAD_ACCEPTED, "contentType", contentType).increment()

    /**
     * uploadRejected은 이미지 업로드·검수·파생물 처리의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게
     * 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun uploadRejected(reason: String) {
        meterRegistry.counter(UPLOAD_REJECTED, "reason", reason).increment()
    }

    /**
     * transition은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun transition(status: String, result: String) {
        meterRegistry.counter(TRANSITION, "status", status, "result", result).increment()
    }

    /**
     * cleanup은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun cleanup(result: String) {
        meterRegistry.counter(CLEANUP, "result", result).increment()
    }

    /**
     * moderation은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param durationNanos 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun moderation(durationNanos: Long, result: String) {
        Timer.builder(MODERATION_DURATION)
            .tag("result", result)
            .register(meterRegistry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
    }
}
