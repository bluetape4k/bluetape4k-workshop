package io.bluetape4k.workshop.messaging.kafka

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.messaging.kafka.EmbeddedKafkaTest.Companion.TEST_TOPIC_NAME
import org.amshove.kluent.shouldBeEqualTo
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles

/**
 * Spring-Kafka 의 Embedded Kafka 를 이용하는 테스트 케이스
 *
 * 그냥 Testcontainers 를 사용하는 것과 다를 바 없다. 그냥 Testcontainers 를 사용하는 것을 추천한다.
 */
@ActiveProfiles("embedded")
@SpringBootTest
@EmbeddedKafka(
    topics = [TEST_TOPIC_NAME],
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
)
@Import(EmbeddedKafkaTest.TestListener::class)
class EmbeddedKafkaTest {

    companion object: KLogging() {
        internal const val TEST_TOPIC_NAME = "test-topic.1"

        init {
            System.setProperty(EmbeddedKafkaBroker.BROKER_LIST_PROPERTY, "spring.kafka.bootstrap-servers")
        }
    }

    @Autowired
    private val kafkaTemplate: KafkaTemplate<String, Any?> = uninitialized()

    @Test
    fun `send and receive message`() {
        val message = "test message"
        kafkaTemplate.send(TEST_TOPIC_NAME, message)

        await untilNotNull { TestListener.result }

        TestListener.result shouldBeEqualTo message
    }


    @TestComponent
    class TestListener {
        companion object: KLogging() {
            var result: String? = null
        }

        @KafkaListener(topics = [TEST_TOPIC_NAME])
        fun handle(message: String) {
            log.info { "Received message: $message" }
            result = message
        }
    }
}
