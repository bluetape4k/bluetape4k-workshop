package io.bluetape4k.workshop.aws.ktordynamodb

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.model.BillingMode
import io.bluetape4k.aws.kotlin.dynamodb.model.partitionKeyOf
import io.bluetape4k.aws.kotlin.dynamodb.model.stringAttrDefinitionOf
import io.bluetape4k.aws.ktor.dynamodb.DynamoDbKtorPlugin
import io.bluetape4k.aws.ktor.dynamodb.dynamoDb
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import java.util.concurrent.CancellationException

private object KtorDynamoDbApplicationLogging : KLogging()

fun main() {
    embeddedServer(Netty, port = 8080) {
        ktorDynamoDbApplication(DynamoDbLocalConfig.fromSystemProperties())
    }.start(wait = true)
}

internal fun Application.ktorDynamoDbApplication(config: DynamoDbLocalConfig) {
    installOrderSessionHttpPlugins()

    install(DynamoDbKtorPlugin) {
        endpointUrl = config.endpointUrl
        region = config.region
        credentialsProvider = config.credentialsProvider()
        autoCreateTables = config.mode == AwsWorkshopMode.LOCAL
        tableReadyTimeout = config.tableReadyTimeout
        table(
            tableName = config.tableName,
            keySchema = listOf(partitionKeyOf("id")),
            attributeDefinitions = listOf(stringAttrDefinitionOf("id")),
        ) {
            billingMode = BillingMode.PayPerRequest
        }
    }

    val repository = OrderSessionDynamoRepository(
        runtime = dynamoDb(),
        tableName = config.tableName,
    )
    val service = DynamoDbOrderSessionService(repository = repository, config = config)
    orderSessionRoutes(service)
}

internal fun Application.installOrderSessionHttpPlugins() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = false
            explicitNulls = false
            prettyPrint = true
        })
    }
    install(CallLogging)
    intercept(ApplicationCallPipeline.Plugins) {
        val contentLength = context.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength != null && contentLength > ORDER_SESSION_REQUEST_BODY_LIMIT_BYTES) {
            throw PayloadTooLargeException(contentLength)
        }
    }

    install(StatusPages) {
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse(
                    code = OrderSessionErrorCode.REQUEST_TOO_LARGE.code,
                    message = "Request body exceeds $ORDER_SESSION_REQUEST_BODY_LIMIT_BYTES bytes.",
                ),
            )
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    code = OrderSessionErrorCode.MALFORMED_JSON.code,
                    message = "Request body must be valid JSON.",
                ),
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    code = OrderSessionErrorCode.MALFORMED_JSON.code,
                    message = "Request body must be valid JSON.",
                ),
            )
        }
        exception<OrderSessionException> { call, cause ->
            call.respond(cause.status, cause.toErrorResponse())
        }
        exception<CancellationException> { _, cause ->
            throw cause
        }
        exception<Throwable> { call, cause ->
            KtorDynamoDbApplicationLogging.log.warn(cause) {
                "DynamoDB workshop request failed with safeClass=${cause::class.simpleName}."
            }
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    code = OrderSessionErrorCode.DYNAMODB_UNAVAILABLE.code,
                    message = "DynamoDB is unavailable.",
                ),
            )
        }
    }
}

private fun DynamoDbLocalConfig.credentialsProvider(): StaticCredentialsProvider? {
    val accessKey = accessKeyId ?: return null
    val secretKey = secretAccessKey ?: return null
    return StaticCredentialsProvider {
        accessKeyId = accessKey
        secretAccessKey = secretKey
    }
}
