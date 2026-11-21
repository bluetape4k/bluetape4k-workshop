package io.bluetape4k.workshop.commerce.usagebilling.billing.messaging

import io.bluetape4k.concurrent.get
import io.bluetape4k.logging.KLogging
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds
/**
 * KafkaBillingEventTransport은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property kafkaTemplate 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class KafkaBillingEventTransport(
    /**
     * `kafkaTemplate`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val kafkaTemplate: KafkaTemplate<String, String>,
): BillingEventTransport {

    private companion object: KLogging() {
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val TOPIC = "billing.events.v1"
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        const val PUBLISH_TIMEOUT_SECONDS = 5L
    }

    /**
     * publish은 사용량 계량·청구 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param partitionKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param payload 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    @Suppress("ThrowsCount")
    override fun publish(partitionKey: String, payload: String) {
        try {
            kafkaTemplate.send(TOPIC, partitionKey, payload).get(PUBLISH_TIMEOUT_SECONDS.seconds)
        } catch (failure: TimeoutException) {
            throw BillingEventTransportFailure(failure)
        } catch (failure: ExecutionException) {
            throw BillingEventTransportFailure(failure)
        } catch (failure: KafkaException) {
            throw BillingEventTransportFailure(failure)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BillingEventTransportFailure(failure)
        }
    }
}
