package io.bluetape4k.workshop.commerce.metering.config

import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
/**
 * MeteringMetrics은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property registry 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class MeteringMetrics(private val registry: MeterRegistry) {

    companion object: KLogging()

    /**
     * command은 사용량 계량·청구 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun command(operation: String, result: String): Unit =
        Counter.builder("workshop.metering.commands")
            .tag("operation", operation)
            .tag("result", result)
            .register(registry)
            .increment()

    /**
     * close은 사용량 계량·청구의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun close(result: String): Unit =
        Counter.builder("workshop.metering.close.batches")
            .tag("result", result)
            .register(registry)
            .increment()
}
