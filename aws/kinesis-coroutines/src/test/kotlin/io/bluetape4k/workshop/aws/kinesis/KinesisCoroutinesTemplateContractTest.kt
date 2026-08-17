package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.spring.kinesis.KinesisCoroutinesTemplate
import io.bluetape4k.aws.spring.kinesis.KinesisProperties
import io.bluetape4k.aws.spring.kinesis.KinesisRecordFlowOptions
import io.bluetape4k.aws.spring.kinesis.KinesisRecordFlowRequest
import io.bluetape4k.aws.spring.kinesis.KinesisStartingPosition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.ExpiredIteratorException
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse
import software.amazon.awssdk.services.kinesis.model.KinesisException
import software.amazon.awssdk.services.kinesis.model.ProvisionedThroughputExceededException
import software.amazon.awssdk.services.kinesis.model.Record
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import java.time.Instant

class KinesisCoroutinesTemplateContractTest {

    @Test
    fun `public upstream template bridges completed futures and preserves empty flow`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            CompletableFuture.completedFuture(
                GetShardIteratorResponse.builder().shardIterator("iterator-1").build()
            )
        every { client.getRecords(any<GetRecordsRequest>()) } returns
            CompletableFuture.completedFuture(GetRecordsResponse.builder().build())

        val template = KinesisCoroutinesTemplate(client, KinesisProperties())
        val records = template.recordFlow(request()).toList()

        records shouldBeEqualTo emptyList()
    }

    @Test
    fun `cancelling collector cancels pending upstream future`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val pending = CompletableFuture<GetShardIteratorResponse>()
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns pending

        val template = KinesisCoroutinesTemplate(client, KinesisProperties())
        val collector = launch {
            template.recordFlow(request()).toList()
        }
        testScheduler.advanceUntilIdle()
        collector.cancelAndJoin()

        pending.isCancelled shouldBeEqualTo true
    }

    @Test
    fun `direct flow options preserve zero retry budget contract`() {
        val options = KinesisRecordFlowOptions(maxIteratorRetries = 0, maxThrottleRetries = 0)

        options.maxIteratorRetries shouldBeEqualTo 0
        options.maxThrottleRetries shouldBeEqualTo 0
    }

    @Test
    fun `expired iterator resumes from the same position before any record`() = runTest {
        val positions = listOf(
            KinesisStartingPosition.TrimHorizon to ShardIteratorType.TRIM_HORIZON,
            KinesisStartingPosition.AtTimestamp(Instant.parse("2026-08-17T00:00:00Z")) to
                ShardIteratorType.AT_TIMESTAMP,
            KinesisStartingPosition.AtSequenceNumber("42") to ShardIteratorType.AT_SEQUENCE_NUMBER,
            KinesisStartingPosition.AfterSequenceNumber("42") to ShardIteratorType.AFTER_SEQUENCE_NUMBER,
        )

        positions.forEach { (position, expectedType) ->
            val client = mockk<KinesisAsyncClient>()
            val iteratorRequests = mutableListOf<GetShardIteratorRequest>()
            val expired = ExpiredIteratorException.builder().message("expired").build()
            every { client.getShardIterator(any<GetShardIteratorRequest>()) } answers {
                iteratorRequests += firstArg<GetShardIteratorRequest>()
                CompletableFuture.completedFuture(
                    GetShardIteratorResponse.builder()
                        .shardIterator("iterator-${iteratorRequests.size}")
                        .build()
                )
            }
            every { client.getRecords(any<GetRecordsRequest>()) } returnsMany listOf(
                CompletableFuture.failedFuture(expired),
                CompletableFuture.completedFuture(GetRecordsResponse.builder().build()),
            )

            KinesisCoroutinesTemplate(client, KinesisProperties())
                .recordFlow(request(position, options = retryOptions()))
                .toList()

            iteratorRequests.size shouldBeEqualTo 2
            iteratorRequests[0].shardIteratorType() shouldBeEqualTo expectedType
            iteratorRequests[1].shardIteratorType() shouldBeEqualTo expectedType
            if (position is KinesisStartingPosition.AtSequenceNumber) {
                iteratorRequests[1].startingSequenceNumber() shouldBeEqualTo position.sequenceNumber
            }
            if (position is KinesisStartingPosition.AfterSequenceNumber) {
                iteratorRequests[1].startingSequenceNumber() shouldBeEqualTo position.sequenceNumber
            }
            if (position is KinesisStartingPosition.AtTimestamp) {
                iteratorRequests[1].timestamp() shouldBeEqualTo position.timestamp
            }
        }
    }

    @Test
    fun `latest expired iterator propagates without retry`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val expired = ExpiredIteratorException.builder().message("latest expired").build()
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            CompletableFuture.completedFuture(GetShardIteratorResponse.builder().shardIterator("iterator").build())
        every { client.getRecords(any<GetRecordsRequest>()) } returns CompletableFuture.failedFuture(expired)

        val error = assertFailsWith<ExpiredIteratorException> {
            KinesisCoroutinesTemplate(client, KinesisProperties())
                .recordFlow(request(KinesisStartingPosition.Latest, options = retryOptions()))
                .toList()
        }

        error shouldBeEqualTo expired
        verify(exactly = 1) { client.getShardIterator(any<GetShardIteratorRequest>()) }
    }

    @Test
    fun `expired iterator after emitted record resumes after the last sequence`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val iteratorRequests = mutableListOf<GetShardIteratorRequest>()
        val expired = ExpiredIteratorException.builder().message("expired after record").build()
        val record = record("42")
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } answers {
            iteratorRequests += firstArg<GetShardIteratorRequest>()
            CompletableFuture.completedFuture(
                GetShardIteratorResponse.builder().shardIterator("iterator-${iteratorRequests.size}").build()
            )
        }
        every { client.getRecords(any<GetRecordsRequest>()) } returnsMany listOf(
            CompletableFuture.completedFuture(
                GetRecordsResponse.builder().records(record).nextShardIterator("iterator-next").build()
            ),
            CompletableFuture.failedFuture(expired),
            CompletableFuture.completedFuture(GetRecordsResponse.builder().build()),
        )

        val records = KinesisCoroutinesTemplate(client, KinesisProperties())
            .recordFlow(request(options = retryOptions()))
            .toList()

        records.map { it.sequenceNumber() } shouldBeEqualTo listOf("42")
        iteratorRequests.size shouldBeEqualTo 2
        iteratorRequests[1].shardIteratorType() shouldBeEqualTo ShardIteratorType.AFTER_SEQUENCE_NUMBER
        iteratorRequests[1].startingSequenceNumber() shouldBeEqualTo "42"
    }

    @Test
    fun `throttle retry uses its own budget and preserves non throttle failures`() = runTest {
        val throttledClient = mockk<KinesisAsyncClient>()
        every { throttledClient.getShardIterator(any<GetShardIteratorRequest>()) } returns
            CompletableFuture.completedFuture(GetShardIteratorResponse.builder().shardIterator("iterator").build())
        every { throttledClient.getRecords(any<GetRecordsRequest>()) } returnsMany listOf(
            CompletableFuture.failedFuture(
                throttled()
            ),
            CompletableFuture.completedFuture(GetRecordsResponse.builder().build()),
        )

        KinesisCoroutinesTemplate(throttledClient, KinesisProperties())
            .recordFlow(request(options = retryOptions(maxThrottleRetries = 1)))
            .toList()

        verify(exactly = 2) { throttledClient.getRecords(any<GetRecordsRequest>()) }

        val failure = KinesisException.builder().message("upstream failure").build()
        val failingClient = mockk<KinesisAsyncClient>()
        every { failingClient.getShardIterator(any<GetShardIteratorRequest>()) } returns
            CompletableFuture.completedFuture(GetShardIteratorResponse.builder().shardIterator("iterator").build())
        every { failingClient.getRecords(any<GetRecordsRequest>()) } returns CompletableFuture.failedFuture(failure)

        val error = assertFailsWith<KinesisException> {
            KinesisCoroutinesTemplate(failingClient, KinesisProperties())
                .recordFlow(request(options = retryOptions()))
                .toList()
        }

        error shouldBeEqualTo failure
    }

    @Test
    fun `successful record episode resets iterator and throttle retry counters`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        val expired = ExpiredIteratorException.builder().message("expired").build()
        val throttled = throttled()
        val iteratorRequests = mutableListOf<GetShardIteratorRequest>()
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } answers {
            iteratorRequests += firstArg<GetShardIteratorRequest>()
            CompletableFuture.completedFuture(
                GetShardIteratorResponse.builder().shardIterator("iterator-${iteratorRequests.size}").build()
            )
        }
        every { client.getRecords(any<GetRecordsRequest>()) } returnsMany listOf(
            CompletableFuture.failedFuture(throttled),
            CompletableFuture.failedFuture(expired),
            CompletableFuture.completedFuture(
                GetRecordsResponse.builder().records(record("1")).nextShardIterator("iterator-next").build()
            ),
            CompletableFuture.failedFuture(expired),
            CompletableFuture.completedFuture(GetRecordsResponse.builder().build()),
        )

        val records = KinesisCoroutinesTemplate(client, KinesisProperties())
            .recordFlow(request(options = retryOptions()))
            .toList()

        records.map { it.sequenceNumber() } shouldBeEqualTo listOf("1")
        iteratorRequests.size shouldBeEqualTo 3
    }

    @Test
    fun `throttle backoff is capped and cancellation stops pending retry`() = runTest {
        val client = mockk<KinesisAsyncClient>()
        every { client.getShardIterator(any<GetShardIteratorRequest>()) } returns
            CompletableFuture.completedFuture(GetShardIteratorResponse.builder().shardIterator("iterator").build())
        val recordsCalls = mutableListOf<Int>()
        every { client.getRecords(any<GetRecordsRequest>()) } answers {
            recordsCalls += 1
            CompletableFuture.failedFuture(
                throttled()
            )
        }
        val options = retryOptions(
            maxThrottleRetries = 2,
            initialThrottleBackoff = Duration.ofSeconds(3),
            maxThrottleBackoff = Duration.ofSeconds(1),
        )

        val collector: Job = launch {
            KinesisCoroutinesTemplate(client, KinesisProperties())
                .recordFlow(request(options = options))
                .toList()
        }
        runCurrent()
        recordsCalls.size shouldBeEqualTo 1
        advanceTimeBy(999)
        runCurrent()
        recordsCalls.size shouldBeEqualTo 1
        advanceTimeBy(1)
        runCurrent()
        recordsCalls.size shouldBeEqualTo 2

        collector.cancelAndJoin()
        recordsCalls.size shouldBeEqualTo 2
    }

    private fun request(
        position: KinesisStartingPosition = KinesisStartingPosition.TrimHorizon,
        options: KinesisRecordFlowOptions = retryOptions(),
    ) = KinesisRecordFlowRequest(
        streamName = "orders",
        shardId = KinesisWorkshopProperties.DEFAULT_SHARD_ID,
        position = position,
        options = options,
    )

    private fun retryOptions(
        maxIteratorRetries: Int = 1,
        maxThrottleRetries: Int = 1,
        initialThrottleBackoff: Duration = Duration.ZERO,
        maxThrottleBackoff: Duration = Duration.ZERO,
    ) = KinesisRecordFlowOptions(
            batchLimit = 1,
            pollInterval = Duration.ZERO,
            emptyBackoff = Duration.ZERO,
            maxIteratorRetries = maxIteratorRetries,
            maxThrottleRetries = maxThrottleRetries,
            initialThrottleBackoff = initialThrottleBackoff,
            maxThrottleBackoff = maxThrottleBackoff,
            jitterRatio = 0.0,
        )

    private fun record(sequenceNumber: String): Record = Record.builder()
        .sequenceNumber(sequenceNumber)
        .partitionKey("partition")
        .data(SdkBytes.fromUtf8String("payload-$sequenceNumber"))
        .build()

    private fun throttled(): ProvisionedThroughputExceededException =
        ProvisionedThroughputExceededException.builder()
            .message("throttled")
            .awsErrorDetails(
                AwsErrorDetails.builder()
                    .errorCode("ProvisionedThroughputExceededException")
                    .build()
            )
            .build()
}
