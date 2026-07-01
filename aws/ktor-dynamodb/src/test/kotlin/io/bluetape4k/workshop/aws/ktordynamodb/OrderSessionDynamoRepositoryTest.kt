package io.bluetape4k.workshop.aws.ktordynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemResponse
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.GetItemResponse
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemResponse
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import aws.sdk.kotlin.services.dynamodb.model.ScanResponse
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OrderSessionDynamoRepositoryTest {

    private val client: DynamoDbClient = mockk()
    private lateinit var repository: OrderSessionDynamoRepository

    @BeforeEach
    fun setUp() {
        clearMocks(client)
        repository = OrderSessionDynamoRepository(
            dynamoDbClient = client,
            tableName = "order-sessions",
        )
    }

    @Test
    fun `create uses one conditional PutItem command`() = runSuspendIO {
        val request = slot<PutItemRequest>()
        coEvery { client.putItem(capture(request)) } returns PutItemResponse {}

        val saved = repository.create(
            OrderSession(
                id = "order-1001",
                customerId = "customer-42",
                status = OrderSessionStatus.CREATED,
                notes = "new order",
                version = 1L,
            ),
        )

        saved.id shouldBeEqualTo "order-1001"
        request.captured.tableName shouldBeEqualTo "order-sessions"
        request.captured.conditionExpression shouldBeEqualTo "attribute_not_exists(#id)"
        request.captured.item?.getValue("version")?.asN() shouldBeEqualTo "1"
        coVerify(exactly = 1) { client.putItem(any<PutItemRequest>()) }
        confirmVerified(client)
    }

    @Test
    fun `findById uses one GetItem command`() = runSuspendIO {
        val request = slot<GetItemRequest>()
        coEvery { client.getItem(capture(request)) } returns GetItemResponse {
            item = orderItem(id = "order-1001", version = 2L)
        }

        val found = repository.findById("order-1001")

        found?.id shouldBeEqualTo "order-1001"
        found?.version shouldBeEqualTo 2L
        request.captured.key?.getValue("id")?.asS() shouldBeEqualTo "order-1001"
        coVerify(exactly = 1) { client.getItem(any<GetItemRequest>()) }
        confirmVerified(client)
    }

    @Test
    fun `list uses one bounded Scan command`() = runSuspendIO {
        val request = slot<ScanRequest>()
        coEvery { client.scan(capture(request)) } returns ScanResponse {
            items = listOf(orderItem(id = "order-1001", version = 1L))
            lastEvaluatedKey = mapOf("id" to AttributeValue.S("order-1001"))
        }

        val response = repository.list(limit = 25, nextToken = null)

        response.items.single().id shouldBeEqualTo "order-1001"
        request.captured.limit shouldBeEqualTo 25
        coVerify(exactly = 1) { client.scan(any<ScanRequest>()) }
        confirmVerified(client)
    }

    @Test
    fun `update uses one conditional UpdateItem command`() = runSuspendIO {
        val request = slot<UpdateItemRequest>()
        coEvery { client.updateItem(capture(request)) } returns UpdateItemResponse {
            attributes = orderItem(
                id = "order-1001",
                status = OrderSessionStatus.APPROVED,
                notes = "approved",
                version = 2L,
            )
        }

        val updated = repository.update(
            id = "order-1001",
            expectedVersion = 1L,
            status = OrderSessionStatus.APPROVED,
            notes = "approved",
        )

        updated.status shouldBeEqualTo OrderSessionStatus.APPROVED
        updated.version shouldBeEqualTo 2L
        request.captured.conditionExpression shouldBeEqualTo "attribute_exists(#id) AND #version = :expectedVersion"
        request.captured.returnValues?.value shouldBeEqualTo "ALL_NEW"
        coVerify(exactly = 1) { client.updateItem(any<UpdateItemRequest>()) }
        confirmVerified(client)
    }

    @Test
    fun `update maps conditional failure to version conflict after one lookup`() = runSuspendIO {
        coEvery { client.updateItem(any<UpdateItemRequest>()) } throws
            ConditionalCheckFailedException { message = "condition failed" }
        coEvery { client.getItem(any<GetItemRequest>()) } returns GetItemResponse {
            item = orderItem(id = "order-1001", version = 3L)
        }

        val failure = assertFailsWith<OrderSessionConflictException> {
            repository.update(
                id = "order-1001",
                expectedVersion = 2L,
                status = OrderSessionStatus.APPROVED,
                notes = "approved",
            )
        }

        failure.errorCode shouldBeEqualTo OrderSessionErrorCode.ORDER_SESSION_VERSION_CONFLICT
        coVerify(exactly = 1) { client.updateItem(any<UpdateItemRequest>()) }
        coVerify(exactly = 1) { client.getItem(any<GetItemRequest>()) }
        confirmVerified(client)
    }

    @Test
    fun `delete uses one conditional DeleteItem command`() = runSuspendIO {
        val request = slot<DeleteItemRequest>()
        coEvery { client.deleteItem(capture(request)) } returns DeleteItemResponse {}

        repository.delete("order-1001")

        request.captured.conditionExpression shouldBeEqualTo "attribute_exists(#id)"
        request.captured.key?.getValue("id")?.asS() shouldBeEqualTo "order-1001"
        coVerify(exactly = 1) { client.deleteItem(any<DeleteItemRequest>()) }
        confirmVerified(client)
    }

    private fun orderItem(
        id: String,
        status: OrderSessionStatus = OrderSessionStatus.CREATED,
        notes: String = "",
        version: Long,
    ): Map<String, AttributeValue> =
        mapOf(
            "id" to AttributeValue.S(id),
            "customerId" to AttributeValue.S("customer-42"),
            "status" to AttributeValue.S(status.name),
            "notes" to AttributeValue.S(notes),
            "version" to AttributeValue.N(version.toString()),
        )
}
