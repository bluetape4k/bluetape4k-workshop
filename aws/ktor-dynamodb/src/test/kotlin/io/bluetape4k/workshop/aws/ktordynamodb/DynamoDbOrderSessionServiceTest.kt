package io.bluetape4k.workshop.aws.ktordynamodb

import aws.smithy.kotlin.runtime.net.url.Url
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.Serializable as JavaSerializable

class DynamoDbOrderSessionServiceTest {

    private lateinit var repository: FakeOrderSessionRepository
    private lateinit var service: DynamoDbOrderSessionService

    @BeforeEach
    fun setUp() {
        repository = FakeOrderSessionRepository()
        service = DynamoDbOrderSessionService(
            repository = repository,
            config = DynamoDbLocalConfig(
                mode = AwsWorkshopMode.LOCAL,
                emulator = AwsWorkshopEmulator.FLOCI,
                region = "ap-northeast-2",
                tableName = "test-order-sessions",
                endpointUrl = Url.parse("http://localhost:8000"),
                accessKeyId = "test",
                secretAccessKey = "test",
                tableReadyTimeout = kotlin.time.Duration.ZERO,
            ),
        )
    }

    @Test
    fun `create stores a new order session with version one`() = runSuspendIO {
        val response = service.create(
            CreateOrderSessionRequest(
                id = "order-1001",
                customerId = "customer-42",
                notes = "fragile package",
            ),
        )

        response.id shouldBeEqualTo "order-1001"
        response.customerId shouldBeEqualTo "customer-42"
        response.status shouldBeEqualTo OrderSessionStatus.CREATED
        response.notes shouldBeEqualTo "fragile package"
        response.version shouldBeEqualTo 1L
        repository.createdSessions.single().version shouldBeEqualTo 1L
    }

    @Test
    fun `create rejects blank identifiers before touching DynamoDB`() = runSuspendIO {
        val failure = assertFailsWith<OrderSessionValidationException> {
            service.create(
                CreateOrderSessionRequest(
                    id = " ",
                    customerId = "customer-42",
                ),
            )
        }

        failure.errorCode shouldBeEqualTo OrderSessionErrorCode.VALIDATION_FAILED
        repository.createdSessions.size shouldBeEqualTo 0
    }

    @Test
    fun `findById maps a missing item to not found`() = runSuspendIO {
        val failure = assertFailsWith<OrderSessionNotFoundException> {
            service.findById("order-missing")
        }

        failure.errorCode shouldBeEqualTo OrderSessionErrorCode.ORDER_SESSION_NOT_FOUND
        repository.findByIdCalls shouldBeEqualTo listOf("order-missing")
    }

    @Test
    fun `list uses the default bounded page size`() = runSuspendIO {
        repository.listResponse = OrderSessionListResponse(
            items = listOf(
                OrderSessionResponse(
                    id = "order-1001",
                    customerId = "customer-42",
                    status = OrderSessionStatus.APPROVED,
                    notes = "ready",
                    version = 2L,
                ),
            ),
            nextToken = "opaque-token",
        )

        val response = service.list(limit = null, nextToken = null)

        response.items.single().id shouldBeEqualTo "order-1001"
        response.nextToken shouldBeEqualTo "opaque-token"
        repository.listCalls shouldBeEqualTo listOf(ListCall(limit = 25, nextToken = null))
    }

    @Test
    fun `list rejects limits outside the public range before touching DynamoDB`() = runSuspendIO {
        val failure = assertFailsWith<OrderSessionValidationException> {
            service.list(limit = 101, nextToken = null)
        }

        failure.errorCode shouldBeEqualTo OrderSessionErrorCode.VALIDATION_FAILED
        repository.listCalls.size shouldBeEqualTo 0
    }

    @Test
    fun `update passes expected version and maps the updated session`() = runSuspendIO {
        repository.updatedSession = OrderSession(
            id = "order-1001",
            customerId = "customer-42",
            status = OrderSessionStatus.APPROVED,
            notes = "approved by reviewer",
            version = 2L,
        )

        val response = service.update(
            id = "order-1001",
            request = UpdateOrderSessionRequest(
                expectedVersion = 1L,
                status = OrderSessionStatus.APPROVED,
                notes = "approved by reviewer",
            ),
        )

        response.status shouldBeEqualTo OrderSessionStatus.APPROVED
        response.version shouldBeEqualTo 2L
        repository.updateCalls shouldBeEqualTo
            listOf(UpdateCall("order-1001", 1L, OrderSessionStatus.APPROVED, "approved by reviewer"))
    }

    @Test
    fun `delete validates the route id and delegates once`() = runSuspendIO {
        service.delete("order-1001")

        repository.deleteCalls shouldBeEqualTo listOf("order-1001")
    }

    @Test
    fun `readiness reports local emulator metadata`() = runSuspendIO {
        val response = service.readiness()

        response.status shouldBeEqualTo "UP"
        response.mode shouldBeEqualTo "LOCAL"
        response.emulator shouldBeEqualTo "FLOCI"
        response.region shouldBeEqualTo "ap-northeast-2"
        response.tableName shouldBeEqualTo "test-order-sessions"
        response.tableReady shouldBeEqualTo true
    }

    private class FakeOrderSessionRepository : OrderSessionRepository {
        val createdSessions = mutableListOf<OrderSession>()
        val findByIdCalls = mutableListOf<String>()
        val listCalls = mutableListOf<ListCall>()
        val updateCalls = mutableListOf<UpdateCall>()
        val deleteCalls = mutableListOf<String>()
        var listResponse = OrderSessionListResponse(items = emptyList())
        var updatedSession = OrderSession(
            id = "order-1001",
            customerId = "customer-42",
            status = OrderSessionStatus.CREATED,
            notes = "",
            version = 1L,
        )
        var tableReady = true

        override suspend fun create(session: OrderSession): OrderSession {
            createdSessions += session
            return session
        }

        override suspend fun findById(id: String): OrderSession? {
            findByIdCalls += id
            return null
        }

        override suspend fun list(limit: Int, nextToken: String?): OrderSessionListResponse =
            listResponse.also {
                listCalls += ListCall(limit = limit, nextToken = nextToken)
            }

        override suspend fun update(
            id: String,
            expectedVersion: Long,
            status: OrderSessionStatus,
            notes: String,
        ): OrderSession {
            updateCalls += UpdateCall(
                id = id,
                expectedVersion = expectedVersion,
                status = status,
                notes = notes,
            )
            return updatedSession
        }

        override suspend fun delete(id: String) {
            deleteCalls += id
        }

        override suspend fun isTableReady(): Boolean = tableReady
    }

    private data class ListCall(
        val limit: Int,
        val nextToken: String?,
    ) : JavaSerializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private data class UpdateCall(
        val id: String,
        val expectedVersion: Long,
        val status: OrderSessionStatus,
        val notes: String,
    ) : JavaSerializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
