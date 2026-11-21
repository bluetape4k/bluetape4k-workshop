package io.bluetape4k.workshop.commerce.usagebilling.composition.fixture

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.KafkaMeterEventTransport
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterEventTransport
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterEventTransportFailure
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** composition test를 위한 deterministic transport fault injection입니다. 정상 delivery는 계속 Kafka를 사용합니다. */
/** composition test를 위한 deterministic transport fault injection입니다. 정상 delivery는 계속 Kafka를 사용합니다. */
@TestConfiguration(proxyBeanMethods = false)
class MeterCompositionTestConfiguration {

    companion object: KLogging()

    @Bean
/**
 * meterTransportFailureSwitch은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun meterTransportFailureSwitch(): MeterTransportFailureSwitch = MeterTransportFailureSwitch()

    @Bean
    @Primary
/**
 * meterCompositionTransport은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun meterCompositionTransport(
        failureSwitch: MeterTransportFailureSwitch,
        delegate: KafkaMeterEventTransport,
    ): MeterEventTransport =
        object: MeterEventTransport {
/**
 * publish은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun publish(partitionKey: String, payload: String) {
                if (failureSwitch.isFailing) {
                    throw MeterEventTransportFailure(IllegalStateException("composition_meter_transport_unavailable"))
                }
                delegate.publish(partitionKey, payload)
            }
        }
}

/**
 * MeterTransportFailureSwitch 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class MeterTransportFailureSwitch {
    @Volatile
/**
 * isFailing은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    var isFailing: Boolean = false
}
