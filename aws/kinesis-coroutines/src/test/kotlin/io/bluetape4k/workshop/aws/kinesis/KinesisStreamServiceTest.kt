package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.spring.kinesis.KinesisRecordFlowOptions
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse
import software.amazon.awssdk.services.kinesis.model.ResourceInUseException
import software.amazon.awssdk.services.kinesis.model.StreamDescription
import software.amazon.awssdk.services.kinesis.model.StreamStatus

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KinesisStreamServiceTest {

    @Test
    fun `ensure creates local stream once and observes active readiness`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        val service = service(operations)

        service.ensureStream().status shouldBeEqualTo KinesisStreamStatus.ACTIVE
        service.ensureStream().status shouldBeEqualTo KinesisStreamStatus.ACTIVE
        operations.describeStream("orders").streamDescription().shards().size shouldBeEqualTo 1
    }

    @Test
    fun `publishes JSON with partition key and consumes ordered cold flow`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        val service = service(operations)
        service.ensureStream()

        val events = (1..3).map { ordinal ->
            KinesisEvent(
                eventId = "event-$ordinal",
                partitionKey = "orders",
                ordinal = ordinal,
                payload = "payload-$ordinal",
            )
        }
        val reports = events.map { service.publish(it) }
        reports.map { it.ordinal } shouldBeEqualTo listOf(1, 2, 3)
        reports.map { it.sequenceNumber.toLong() } shouldBeEqualTo listOf(1L, 2L, 3L)

        val consumed = service.consume().toList()
        consumed.map { it.event.ordinal } shouldBeEqualTo listOf(1, 2, 3)
        consumed.map { it.event.payload } shouldBeEqualTo listOf("payload-1", "payload-2", "payload-3")
        consumed.map { it.partitionKey } shouldBeEqualTo listOf("orders", "orders", "orders")
    }

    @Test
    fun `take cancellation keeps collector passive and does not close fake`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        val service = service(operations)
        service.ensureStream()
        repeat(3) { ordinal ->
            service.publish(
                KinesisEvent("event-$ordinal", "orders", ordinal, "payload-$ordinal")
            )
        }

        val consumed = service.consume().take(1).toList()

        consumed.size shouldBeEqualTo 1
        service.activeCollectorCount shouldBeEqualTo 0
        operations.closed shouldBeEqualTo false
        (operations.getRecordsCalls > 0) shouldBeEqualTo true
    }

    @Test
    fun `closed scope rejects new collector without leaking active count`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        val scope = KinesisDemoScope()
        val service = service(operations, scope)
        service.ensureStream()
        scope.closeAdmission()

        assertFailsWith<CancellationException> {
            service.consume().toList()
        }

        service.activeCollectorCount shouldBeEqualTo 0
        scope.callerCollectorCount shouldBeEqualTo 0
        scope.close()
    }

    @Test
    fun `ensure polls creating stream until active`() = runSuspendIO {
        val operations = mockk<io.bluetape4k.aws.spring.kinesis.KinesisOperations>()
        coEvery { operations.describeStream("orders") } returnsMany listOf(
            description(StreamStatus.CREATING),
            description(StreamStatus.ACTIVE),
        )
        val service = service(operations)

        service.ensureStream().status shouldBeEqualTo KinesisStreamStatus.ACTIVE
    }

    @Test
    fun `ensure returns failed for terminal stream status`() = runSuspendIO {
        val operations = mockk<io.bluetape4k.aws.spring.kinesis.KinesisOperations>()
        coEvery { operations.describeStream("orders") } returns description(StreamStatus.DELETING)
        val service = service(operations)

        service.ensureStream().status shouldBeEqualTo KinesisStreamStatus.FAILED
    }

    @Test
    fun `ensure rechecks when another caller wins create race`() = runSuspendIO {
        val operations = mockk<io.bluetape4k.aws.spring.kinesis.KinesisOperations>()
        var describeCalls = 0
        coEvery { operations.describeStream("orders") } answers {
            if (describeCalls++ == 0) {
                throw software.amazon.awssdk.services.kinesis.model.ResourceNotFoundException.builder().build()
            }
            description(StreamStatus.ACTIVE)
        }
        coEvery { operations.createStream("orders", 1) } throws ResourceInUseException.builder().build()
        val service = service(operations)

        service.ensureStream().status shouldBeEqualTo KinesisStreamStatus.ACTIVE
    }

    private fun service(operations: LocalKinesisOperations): KinesisStreamService =
        service(operations, null)

    private fun service(
        operations: LocalKinesisOperations,
        demoScope: KinesisDemoScope?,
    ): KinesisStreamService =
        KinesisStreamService(
            properties = KinesisWorkshopProperties(
                streamName = "orders",
                partitionKey = "orders",
                shardId = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
                batchLimit = 2,
                readinessTimeout = Duration.ofSeconds(1),
                readinessPollInterval = Duration.ofMillis(1),
            ),
            operations = operations,
            objectMapper = Jackson.defaultJsonMapper,
            flowOptions = KinesisRecordFlowOptions(batchLimit = 2),
            demoScope = demoScope,
        )

    private fun service(operations: io.bluetape4k.aws.spring.kinesis.KinesisOperations): KinesisStreamService =
        KinesisStreamService(
            properties = KinesisWorkshopProperties(
                streamName = "orders",
                partitionKey = "orders",
                shardId = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
                batchLimit = 2,
                readinessTimeout = Duration.ofSeconds(1),
                readinessPollInterval = Duration.ofMillis(1),
            ),
            operations = operations,
            objectMapper = Jackson.defaultJsonMapper,
            flowOptions = KinesisRecordFlowOptions(batchLimit = 2),
        )

    private fun description(status: StreamStatus): DescribeStreamResponse =
        DescribeStreamResponse.builder()
            .streamDescription(
                StreamDescription.builder()
                    .streamName("orders")
                    .streamStatus(status)
                    .build()
            )
            .build()
}
