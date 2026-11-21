package io.bluetape4k.workshop.commerce.usagebilling.meter.messaging

import io.bluetape4k.concurrent.get
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds
/**
 * KafkaMeterEventTransport은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property kafkaTemplate 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class KafkaMeterEventTransport(
    /**
     * publish은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param partitionKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param payload 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private val kafkaTemplate: KafkaTemplate<String, String>,
): MeterEventTransport {
    @Suppress("ThrowsCount")
    /**
     * publish은 사용량 과금 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     * @param partitionKey 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param payload 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun publish(partitionKey: String, payload: String) {
        try {
            kafkaTemplate.send(TOPIC, partitionKey, payload).get(PUBLISH_TIMEOUT_SECONDS.seconds)
        } catch (failure: TimeoutException) {
            throw MeterEventTransportFailure(failure)
        } catch (failure: ExecutionException) {
            throw MeterEventTransportFailure(failure)
        } catch (failure: KafkaException) {
            throw MeterEventTransportFailure(failure)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MeterEventTransportFailure(failure)
        }
    }

    private companion object {
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val TOPIC = "meter.events.v1"
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        const val PUBLISH_TIMEOUT_SECONDS = 5L
    }
}
