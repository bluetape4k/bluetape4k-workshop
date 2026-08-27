package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.apache.kafka.common.errors.TimeoutException
import org.junit.jupiter.api.Test

class KafkaFailoverRetryTest {

    @Test
    fun `retry count excludes the first admin attempt and caps at five`() {
        var attempts = 0
        val retry = KafkaFailoverRetry(sleep = {})
        val deadline = KafkaFailoverDeadline.fromNow(KafkaFailoverDeadline.MODULE_TIMEOUT)

        val result = retry.execute(deadline, "metadata") {
            attempts += 1
            if (attempts < 3) {
                throw TimeoutException("temporary metadata timeout")
            }
            "ready"
        }

        result.value shouldBeEqualTo "ready"
        result.retryCount shouldBeEqualTo 2
        attempts shouldBeEqualTo 3
        KafkaFailoverRetry.MAX_RETRY_ATTEMPTS shouldBeEqualTo 5
    }

    @Test
    fun `non transient error is not retried`() {
        var attempts = 0
        val retry = KafkaFailoverRetry(sleep = {})
        val deadline = KafkaFailoverDeadline.fromNow(KafkaFailoverDeadline.MODULE_TIMEOUT)

        assertFailsWith<IllegalStateException> {
            retry.execute(deadline, "config") {
                attempts += 1
                throw IllegalStateException("configuration rejected")
            }
        }

        attempts shouldBeEqualTo 1
    }
}
