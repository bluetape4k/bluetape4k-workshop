package io.bluetape4k.workshop.commerce.metering.worker

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.application.BillingCloseService
import io.bluetape4k.workshop.commerce.metering.config.MeteringMetrics
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
/**
 * BillingCloseScheduler은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property service 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property properties 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class BillingCloseScheduler(
    /**
     * `service`은 사용량 과금·정산에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: BillingCloseService,
    /**
     * `properties`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: MeteringProperties,
    /**
     * `metrics`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: MeteringMetrics,
) {

    companion object: KLogging()

    /**
     * process은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Scheduled(fixedDelayString = "\${workshop.metering.close.scheduler-delay:PT5S}")
    fun process() {
        if (!properties.close.schedulerEnabled) return
        val processed = service.processAvailable()
        metrics.close(if (processed == 0) "idle" else "processed")
    }
}
