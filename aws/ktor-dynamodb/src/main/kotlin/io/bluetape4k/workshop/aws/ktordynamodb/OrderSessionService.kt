package io.bluetape4k.workshop.aws.ktordynamodb

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.time.Instant

internal interface OrderSessionService {
    suspend fun create(request: CreateOrderSessionRequest): OrderSessionResponse

    suspend fun findById(id: String): OrderSessionResponse

    suspend fun list(limit: Int?, nextToken: String?): OrderSessionListResponse

    suspend fun update(id: String, request: UpdateOrderSessionRequest): OrderSessionResponse

    suspend fun delete(id: String)

    suspend fun readiness(): ReadinessResponse
}

internal class DynamoDbOrderSessionService(
    private val repository: OrderSessionRepository,
    private val config: DynamoDbLocalConfig,
) : OrderSessionService {

    override suspend fun create(request: CreateOrderSessionRequest): OrderSessionResponse {
        val id = requiredNotBlank(request.id, "id")
        val customerId = requiredNotBlank(request.customerId, "customerId")

        val session = repository.create(
            OrderSession(
                id = id,
                customerId = customerId,
                status = request.status,
                notes = request.notes,
                version = INITIAL_ORDER_SESSION_VERSION,
            ),
        )

        return session.toResponse()
    }

    override suspend fun findById(id: String): OrderSessionResponse {
        val requiredId = requiredNotBlank(id, "id")

        return repository.findById(requiredId)
            ?.toResponse()
            ?: throw OrderSessionNotFoundException(requiredId)
    }

    override suspend fun list(limit: Int?, nextToken: String?): OrderSessionListResponse {
        val pageLimit = requiredListLimit(limit ?: ORDER_SESSION_DEFAULT_LIST_LIMIT)
        val trimmedNextToken = nextToken?.trim()?.takeIf { it.isNotBlank() }

        return repository.list(limit = pageLimit, nextToken = trimmedNextToken)
    }

    override suspend fun update(id: String, request: UpdateOrderSessionRequest): OrderSessionResponse {
        val requiredId = requiredNotBlank(id, "id")
        val expectedVersion = requiredPositive(request.expectedVersion, "expectedVersion")

        return repository.update(
            id = requiredId,
            expectedVersion = expectedVersion,
            status = request.status,
            notes = request.notes,
        ).toResponse()
    }

    override suspend fun delete(id: String) {
        repository.delete(requiredNotBlank(id, "id"))
    }

    override suspend fun readiness(): ReadinessResponse {
        val ready = repository.isTableReady()

        return ReadinessResponse(
            status = if (ready) "UP" else "DOWN",
            mode = config.mode.name,
            emulator = config.emulator?.name,
            region = config.region,
            tableName = config.tableName,
            tableReady = ready,
            checkedAt = Instant.now().toString(),
        )
    }

    private fun requiredNotBlank(value: String, name: String): String {
        try {
            value.requireNotBlank(name)
        } catch (e: IllegalArgumentException) {
            throw OrderSessionValidationException(e.message ?: "$name must not be blank.")
        }

        return value.trim()
    }

    private fun requiredPositive(value: Long, name: String): Long {
        try {
            value.requirePositiveNumber(name)
        } catch (e: IllegalArgumentException) {
            throw OrderSessionValidationException(e.message ?: "$name must be positive.")
        }

        return value
    }

    private fun requiredListLimit(value: Int): Int {
        try {
            value.requireInRange(1, ORDER_SESSION_MAX_LIST_LIMIT, "limit")
        } catch (e: IllegalArgumentException) {
            throw OrderSessionValidationException(
                e.message ?: "limit must be between 1 and $ORDER_SESSION_MAX_LIST_LIMIT.",
            )
        }

        return value
    }

    private fun OrderSession.toResponse(): OrderSessionResponse =
        OrderSessionResponse(
            id = id,
            customerId = customerId,
            status = status,
            notes = notes,
            version = version,
        )

    companion object {
        private const val INITIAL_ORDER_SESSION_VERSION: Long = 1L
    }
}
