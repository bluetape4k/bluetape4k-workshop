package io.bluetape4k.workshop.aws.ktordynamodb

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.kotlin.dynamodb.deleteTableIfExists
import io.bluetape4k.aws.kotlin.dynamodb.dynamoDbClientOf
import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsRecordFlowOptions
import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsStartingPosition
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderSessionDynamoDbEmulatorTest {

    private val awsEmulator: AwsEmulatorServer by lazy { FlociServer.Launcher.floci }

    private val cleanupClient: DynamoDbClient by lazy {
        dynamoDbClientOf(
            endpointUrl = Url.parse(awsEmulator.awsEndpoint.toString()),
            region = awsEmulator.regionName,
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = awsEmulator.awsAccessKey
                secretAccessKey = awsEmulator.awsSecretKey
            },
        )
    }

    @Test
    fun `Ktor routes bootstrap DynamoDB table and persist order sessions through local emulator`() = runSuspendIO {
        val tableName = tableName("orders")

        try {
            testApplication {
                application {
                    ktorDynamoDbApplication(config(tableName))
                }

                startApplication()

                val readiness = client.get("/health/readiness")
                readiness.status shouldBeEqualTo HttpStatusCode.OK
                readiness.bodyAsText() shouldContain """"tableReady": true"""

                val created = client.post("/dynamodb/order-sessions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"order-1001","customerId":"customer-42","notes":"new order"}""")
                }
                created.status shouldBeEqualTo HttpStatusCode.Created
                created.bodyAsText() shouldContain """"version": 1"""

                val duplicate = client.post("/dynamodb/order-sessions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"order-1001","customerId":"customer-42","notes":"new order"}""")
                }
                duplicate.status shouldBeEqualTo HttpStatusCode.Conflict
                duplicate.bodyAsText() shouldContain OrderSessionErrorCode.ORDER_SESSION_EXISTS.code

                val found = client.get("/dynamodb/order-sessions/order-1001")
                found.status shouldBeEqualTo HttpStatusCode.OK
                found.bodyAsText() shouldContain """"customerId": "customer-42""""

                val staleUpdate = client.put("/dynamodb/order-sessions/order-1001") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"expectedVersion":99,"status":"APPROVED","notes":"wrong version"}""")
                }
                staleUpdate.status shouldBeEqualTo HttpStatusCode.Conflict
                staleUpdate.bodyAsText() shouldContain OrderSessionErrorCode.ORDER_SESSION_VERSION_CONFLICT.code

                val updated = client.put("/dynamodb/order-sessions/order-1001") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"expectedVersion":1,"status":"APPROVED","notes":"approved"}""")
                }
                updated.status shouldBeEqualTo HttpStatusCode.OK
                updated.bodyAsText() shouldContain """"version": 2"""

                val listed = client.get("/dynamodb/order-sessions?limit=10")
                listed.status shouldBeEqualTo HttpStatusCode.OK
                listed.bodyAsText() shouldContain "order-1001"

                val deleted = client.delete("/dynamodb/order-sessions/order-1001")
                deleted.status shouldBeEqualTo HttpStatusCode.NoContent

                val missing = client.get("/dynamodb/order-sessions/order-1001")
                missing.status shouldBeEqualTo HttpStatusCode.NotFound
                missing.bodyAsText() shouldContain OrderSessionErrorCode.ORDER_SESSION_NOT_FOUND.code
            }
        } finally {
            cleanupClient.deleteTableIfExists(tableName)
        }
    }

    @Test
    fun `Ktor Streams route resumes inclusively and reports at least once duplicate`() = runSuspendIO {
        val tableName = tableName("streams")

        try {
            testApplication {
                application {
                    ktorDynamoDbApplication(
                        config = config(tableName),
                        streamsConfig = streamsConfig(),
                    )
                }

                startApplication()

                val created = client.post("/dynamodb/order-sessions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"stream-order-1001","customerId":"customer-42"}""")
                }
                created.status shouldBeEqualTo HttpStatusCode.Created

                val first = client.post(
                    "/dynamodb/order-sessions/streams/consume?maxRecords=1&startingPosition=trim_horizon",
                )
                first.status shouldBeEqualTo HttpStatusCode.OK
                first.bodyAsText() shouldContain "\"duplicate\": false"
                first.bodyAsText() shouldContain "\"checkpointByShard\""

                val resumed = client.post(
                    "/dynamodb/order-sessions/streams/consume?maxRecords=1&startingPosition=trim_horizon",
                )
                resumed.status shouldBeEqualTo HttpStatusCode.OK
                resumed.bodyAsText() shouldContain "\"duplicate\": true"
                resumed.bodyAsText() shouldContain "stream-order-1001"
            }
        } finally {
            cleanupClient.deleteTableIfExists(tableName)
        }
    }

    private fun config(tableName: String): DynamoDbLocalConfig =
        DynamoDbLocalConfig(
            mode = AwsWorkshopMode.LOCAL,
            emulator = emulatorType(),
            region = awsEmulator.regionName,
            tableName = tableName,
            endpointUrl = Url.parse(awsEmulator.awsEndpoint.toString()),
            accessKeyId = awsEmulator.awsAccessKey,
            secretAccessKey = awsEmulator.awsSecretKey,
            tableReadyTimeout = 30.seconds,
        )

    private fun streamsConfig(): DynamoDbStreamsWorkshopConfig =
        DynamoDbStreamsWorkshopConfig(
            enabled = true,
            startingPosition = DynamoDbStreamsStartingPosition.TrimHorizon,
            maxRecords = 10,
            flowOptions = DynamoDbStreamsRecordFlowOptions(
                pollInterval = 200.milliseconds,
                emptyBackoff = 200.milliseconds,
                maxShardConcurrency = 1,
            ),
        )

    private fun tableName(prefix: String): String =
        "workshop-$prefix-${Base58.randomString(8).lowercase()}"

    private fun emulatorType(): AwsWorkshopEmulator =
        when (System.getProperty("bluetape4k.aws.emulator", "floci").trim().lowercase()) {
            "floci" -> AwsWorkshopEmulator.FLOCI
            "localstack" -> AwsWorkshopEmulator.LOCALSTACK
            else -> AwsWorkshopEmulator.FLOCI
        }

}
