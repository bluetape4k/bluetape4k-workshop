package io.bluetape4k.workshop.aws.ktordynamodb

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.deleteItem
import aws.sdk.kotlin.services.dynamodb.describeTable
import aws.sdk.kotlin.services.dynamodb.getItem
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.ReturnValue
import aws.sdk.kotlin.services.dynamodb.model.TableStatus
import aws.sdk.kotlin.services.dynamodb.putItem
import aws.sdk.kotlin.services.dynamodb.scan
import aws.sdk.kotlin.services.dynamodb.updateItem
import aws.smithy.kotlin.runtime.ServiceException
import io.bluetape4k.aws.ktor.dynamodb.DynamoDbKtorRuntime
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

internal interface OrderSessionRepository {
    suspend fun create(session: OrderSession): OrderSession

    suspend fun findById(id: String): OrderSession?

    suspend fun list(limit: Int, nextToken: String?): OrderSessionListResponse

    suspend fun update(id: String, expectedVersion: Long, status: OrderSessionStatus, notes: String): OrderSession

    suspend fun delete(id: String)

    suspend fun isTableReady(): Boolean
}

internal class OrderSessionDynamoRepository private constructor(
    private val clientProvider: () -> DynamoDbClient,
    private val tableName: String,
) : OrderSessionRepository {

    constructor(
        runtime: DynamoDbKtorRuntime,
        tableName: String,
    ) : this(clientProvider = { runtime.dynamoDbClient }, tableName = tableName)

    internal constructor(
        dynamoDbClient: DynamoDbClient,
        tableName: String,
    ) : this(clientProvider = { dynamoDbClient }, tableName = tableName)

    private val client: DynamoDbClient
        get() = clientProvider()

    override suspend fun create(session: OrderSession): OrderSession {
        try {
            client.putItem {
                tableName = this@OrderSessionDynamoRepository.tableName
                item = session.toDynamoItem()
                conditionExpression = "attribute_not_exists(#id)"
                expressionAttributeNames = mapOf("#id" to ID_ATTRIBUTE)
            }
        } catch (e: ConditionalCheckFailedException) {
            throw OrderSessionConflictException(
                errorCode = OrderSessionErrorCode.ORDER_SESSION_EXISTS,
                message = "Order session '${session.id}' already exists.",
            )
        }

        return session
    }

    override suspend fun findById(id: String): OrderSession? =
        client.getItem {
            tableName = this@OrderSessionDynamoRepository.tableName
            key = keyOf(id)
        }.item?.toOrderSession()

    override suspend fun list(limit: Int, nextToken: String?): OrderSessionListResponse {
        val response = client.scan {
            tableName = this@OrderSessionDynamoRepository.tableName
            this.limit = limit
            exclusiveStartKey = nextToken?.decodePageToken()
        }

        return OrderSessionListResponse(
            items = response.items.orEmpty().map { it.toOrderSession().toResponse() },
            nextToken = response.lastEvaluatedKey?.get(ID_ATTRIBUTE)?.asS()?.encodePageToken(),
        )
    }

    override suspend fun update(
        id: String,
        expectedVersion: Long,
        status: OrderSessionStatus,
        notes: String,
    ): OrderSession {
        try {
            val response = client.updateItem {
                tableName = this@OrderSessionDynamoRepository.tableName
                key = keyOf(id)
                updateExpression = "SET #status = :status, #notes = :notes, #version = #version + :one"
                conditionExpression = "attribute_exists(#id) AND #version = :expectedVersion"
                expressionAttributeNames = mapOf(
                    "#id" to ID_ATTRIBUTE,
                    "#status" to STATUS_ATTRIBUTE,
                    "#notes" to NOTES_ATTRIBUTE,
                    "#version" to VERSION_ATTRIBUTE,
                )
                expressionAttributeValues = mapOf(
                    ":status" to AttributeValue.S(status.name),
                    ":notes" to AttributeValue.S(notes),
                    ":expectedVersion" to AttributeValue.N(expectedVersion.toString()),
                    ":one" to AttributeValue.N("1"),
                )
                returnValues = ReturnValue.AllNew
            }

            return response.attributes?.toOrderSession()
                ?: throw DynamoDbUnavailableException("DynamoDB did not return updated attributes.")
        } catch (e: ConditionalCheckFailedException) {
            val current = findById(id) ?: throw OrderSessionNotFoundException(id)
            throw OrderSessionConflictException(
                errorCode = OrderSessionErrorCode.ORDER_SESSION_VERSION_CONFLICT,
                message = "Order session '$id' expected version $expectedVersion but was ${current.version}.",
            )
        }
    }

    override suspend fun delete(id: String) {
        try {
            client.deleteItem {
                tableName = this@OrderSessionDynamoRepository.tableName
                key = keyOf(id)
                conditionExpression = "attribute_exists(#id)"
                expressionAttributeNames = mapOf("#id" to ID_ATTRIBUTE)
            }
        } catch (e: ConditionalCheckFailedException) {
            throw OrderSessionNotFoundException(id)
        }
    }

    override suspend fun isTableReady(): Boolean =
        try {
            withTimeoutOrNull(READINESS_TIMEOUT) {
                client.describeTable {
                    tableName = this@OrderSessionDynamoRepository.tableName
                }.table?.tableStatus == TableStatus.Active
            } ?: false
        } catch (e: ServiceException) {
            false
        }

    private fun keyOf(id: String): Map<String, AttributeValue> =
        mapOf(ID_ATTRIBUTE to AttributeValue.S(id))

    private fun OrderSession.toDynamoItem(): Map<String, AttributeValue> =
        mapOf(
            ID_ATTRIBUTE to AttributeValue.S(id),
            CUSTOMER_ID_ATTRIBUTE to AttributeValue.S(customerId),
            STATUS_ATTRIBUTE to AttributeValue.S(status.name),
            NOTES_ATTRIBUTE to AttributeValue.S(notes),
            VERSION_ATTRIBUTE to AttributeValue.N(version.toString()),
        )

    private fun Map<String, AttributeValue>.toOrderSession(): OrderSession =
        OrderSession(
            id = getValue(ID_ATTRIBUTE).asS(),
            customerId = getValue(CUSTOMER_ID_ATTRIBUTE).asS(),
            status = OrderSessionStatus.valueOf(getValue(STATUS_ATTRIBUTE).asS()),
            notes = this[NOTES_ATTRIBUTE]?.asS() ?: "",
            version = getValue(VERSION_ATTRIBUTE).asN().toLong(),
        )

    private fun OrderSession.toResponse(): OrderSessionResponse =
        OrderSessionResponse(
            id = id,
            customerId = customerId,
            status = status,
            notes = notes,
            version = version,
        )

    private fun String.encodePageToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

    private fun String.decodePageToken(): Map<String, AttributeValue> =
        try {
            val id = String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
            keyOf(id)
        } catch (e: IllegalArgumentException) {
            throw OrderSessionInvalidPageTokenException()
        }

    companion object {
        private const val ID_ATTRIBUTE: String = "id"
        private const val CUSTOMER_ID_ATTRIBUTE: String = "customerId"
        private const val STATUS_ATTRIBUTE: String = "status"
        private const val NOTES_ATTRIBUTE: String = "notes"
        private const val VERSION_ATTRIBUTE: String = "version"
        private val READINESS_TIMEOUT = 2.seconds
    }
}
