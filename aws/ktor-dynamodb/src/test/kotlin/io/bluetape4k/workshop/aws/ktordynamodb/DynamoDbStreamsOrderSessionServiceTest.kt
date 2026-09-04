package io.bluetape4k.workshop.aws.ktordynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.DescribeTableResponse
import aws.sdk.kotlin.services.dynamodb.model.TableDescription
import aws.sdk.kotlin.services.dynamodb.describeTable
import aws.sdk.kotlin.services.dynamodbstreams.DynamoDbStreamsClient
import aws.sdk.kotlin.services.dynamodbstreams.describeStream
import aws.sdk.kotlin.services.dynamodbstreams.getRecords
import aws.sdk.kotlin.services.dynamodbstreams.getShardIterator
import aws.sdk.kotlin.services.dynamodbstreams.model.DescribeStreamResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.GetRecordsResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.GetShardIteratorResponse
import aws.sdk.kotlin.services.dynamodbstreams.model.Record
import aws.sdk.kotlin.services.dynamodbstreams.model.Shard
import aws.sdk.kotlin.services.dynamodbstreams.model.StreamDescription
import aws.sdk.kotlin.services.dynamodbstreams.model.StreamRecord
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsRecordFlowOptions
import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsStartingPosition
import io.bluetape4k.aws.kotlin.dynamodbstreams.InMemoryDynamoDbStreamsCheckpointStore
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class DynamoDbStreamsOrderSessionServiceTest {

    @Test
    fun `processor failure leaves checkpoint empty and a retry reports duplicate`() = runSuspendIO {
        val fixture = fixture(records = listOf(record("seq-1")))
        val store = InMemoryDynamoDbStreamsCheckpointStore()
        val service = service(fixture, store)

        assertFailsWith<IllegalStateException> {
            service.consume(maxRecords = 1) {
                error("processor failed")
            }
        }
        store.load(STREAM_ARN, SHARD_ID) shouldBeEqualTo null

        val retry = service.consume(maxRecords = 1)
        retry.processed.single().duplicate shouldBeEqualTo true
        store.load(STREAM_ARN, SHARD_ID) shouldBeEqualTo "seq-1"
        service.close()
    }

    @Test
    fun `empty stream completes without inventing a checkpoint`() = runSuspendIO {
        val fixture = fixture(records = emptyList())
        val store = InMemoryDynamoDbStreamsCheckpointStore()
        val report = service(fixture, store).consume(
            maxRecords = 1,
            position = DynamoDbStreamsStartingPosition.Latest,
        )

        report.processed shouldBeEqualTo emptyList()
        report.startingPosition shouldBeEqualTo "latest"
        store.load(STREAM_ARN, SHARD_ID) shouldBeEqualTo null
    }

    @Test
    fun `close is idempotent and prevents a new consume`() = runSuspendIO {
        val fixture = fixture(records = emptyList())
        val service = service(fixture, InMemoryDynamoDbStreamsCheckpointStore())

        service.close()
        service.close()
        coVerify(exactly = 0) { fixture.streamsClient.getRecords(any()) }
        assertFailsWith<IllegalStateException> {
            service.consume(maxRecords = 1)
        }
    }

    private fun service(
        fixture: Fixture,
        store: InMemoryDynamoDbStreamsCheckpointStore,
    ): DynamoDbStreamsOrderSessionService =
        DynamoDbStreamsOrderSessionService(
            dynamoDbClient = fixture.dynamoDbClient,
            streamsClient = fixture.streamsClient,
            tableName = TABLE_NAME,
            config = DynamoDbStreamsWorkshopConfig(
                enabled = true,
                startingPosition = DynamoDbStreamsStartingPosition.TrimHorizon,
                maxRecords = 1,
                flowOptions = DynamoDbStreamsRecordFlowOptions(
                    pollInterval = 200.milliseconds,
                    emptyBackoff = 200.milliseconds,
                    maxShardConcurrency = 1,
                ),
            ),
            checkpointStore = store,
        )

    private fun fixture(records: List<Record>): Fixture {
        val dynamoDbClient = mockk<DynamoDbClient>()
        val streamsClient = mockk<DynamoDbStreamsClient>(relaxed = true)
        coEvery { dynamoDbClient.describeTable(any()) } returns DescribeTableResponse {
            table = TableDescription { latestStreamArn = STREAM_ARN }
        }
        coEvery { streamsClient.describeStream(any()) } returns DescribeStreamResponse {
            streamDescription = StreamDescription {
                shards = listOf(Shard { shardId = SHARD_ID })
            }
        }
        coEvery { streamsClient.getShardIterator(any()) } returns GetShardIteratorResponse {
            shardIterator = SHARD_ITERATOR
        }
        coEvery { streamsClient.getRecords(any()) } returns GetRecordsResponse {
            this.records = records
            nextShardIterator = null
        }
        return Fixture(dynamoDbClient, streamsClient)
    }

    private fun record(sequenceNumber: String): Record = Record {
        dynamodb = StreamRecord { this.sequenceNumber = sequenceNumber }
    }

    private data class Fixture(
        val dynamoDbClient: DynamoDbClient,
        val streamsClient: DynamoDbStreamsClient,
    )

    private companion object {
        const val TABLE_NAME = "workshop-order-sessions"
        const val STREAM_ARN = "arn:aws:dynamodb:ap-northeast-2:000000000000:table/workshop-order-sessions/stream/1"
        const val SHARD_ID = "shard-000000000001"
        const val SHARD_ITERATOR = "iterator-1"
    }
}
