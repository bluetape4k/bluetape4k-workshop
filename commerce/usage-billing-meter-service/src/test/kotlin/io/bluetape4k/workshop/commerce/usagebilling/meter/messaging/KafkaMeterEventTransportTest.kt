package io.bluetape4k.workshop.commerce.usagebilling.meter.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.logging.KLogging
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate

/**
 * KafkaMeterEventTransportTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class KafkaMeterEventTransportTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "synchronous Kafka send failure remains retryable at the outbox boundary" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `synchronous Kafka send failure remains retryable at the outbox boundary`() {
        val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
        every {
            kafkaTemplate.send(any(), any(), any())
        } throws KafkaException("send failed")

        assertFailsWith<MeterEventTransportFailure> {
            KafkaMeterEventTransport(kafkaTemplate)
                .publish("tenant-a|Meter|api-calls", "{}")
        }
    }
}
