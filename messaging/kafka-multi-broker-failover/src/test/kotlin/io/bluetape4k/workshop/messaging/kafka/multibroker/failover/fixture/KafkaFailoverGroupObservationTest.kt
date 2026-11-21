package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.apache.kafka.common.errors.GroupIdNotFoundException
import org.apache.kafka.common.errors.TimeoutException
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

class KafkaFailoverGroupObservationTest {

    @Test
    fun `group registration and known Kafka retry failures are transient`() {
        KafkaFailoverGroupObservation.isTransient(GroupIdNotFoundException("group not registered"))
            .shouldBeTrue()
        KafkaFailoverGroupObservation.isTransient(TimeoutException("coordinator not ready"))
            .shouldBeTrue()
        KafkaFailoverGroupObservation.isTransient(
            ExecutionException(TimeoutException("wrapped coordinator timeout")),
        ).shouldBeTrue()
        KafkaFailoverGroupObservation.isTransient(
            CompletionException(TimeoutException("wrapped coordinator timeout")),
        ).shouldBeTrue()
    }

    @Test
    fun `unrelated wrappers do not become transient from a nested cause`() {
        KafkaFailoverGroupObservation.isTransient(
            IllegalStateException("invalid group state", GroupIdNotFoundException("nested")),
        ).shouldBeFalse()
    }
}
