package io.bluetape4k.workshop.aws.ktordynamodb

import io.ktor.http.HttpStatusCode
import java.io.Serializable

internal enum class OrderSessionErrorCode(val code: String) {
    VALIDATION_FAILED("VALIDATION_FAILED"),
    MALFORMED_JSON("MALFORMED_JSON"),
    REQUEST_TOO_LARGE("REQUEST_TOO_LARGE"),
    INVALID_PAGE_TOKEN("INVALID_PAGE_TOKEN"),
    ORDER_SESSION_EXISTS("ORDER_SESSION_EXISTS"),
    ORDER_SESSION_VERSION_CONFLICT("ORDER_SESSION_VERSION_CONFLICT"),
    ORDER_SESSION_NOT_FOUND("ORDER_SESSION_NOT_FOUND"),
    DYNAMODB_NOT_READY("DYNAMODB_NOT_READY"),
    DYNAMODB_UNAVAILABLE("DYNAMODB_UNAVAILABLE"),
}

internal sealed class OrderSessionException(
    val status: HttpStatusCode,
    val errorCode: OrderSessionErrorCode,
    override val message: String,
) : RuntimeException(message), Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal class OrderSessionValidationException(message: String) : OrderSessionException(
    status = HttpStatusCode.BadRequest,
    errorCode = OrderSessionErrorCode.VALIDATION_FAILED,
    message = message,
)

internal class OrderSessionConflictException(
    errorCode: OrderSessionErrorCode,
    message: String,
) : OrderSessionException(
    status = HttpStatusCode.Conflict,
    errorCode = errorCode,
    message = message,
)

internal class OrderSessionNotFoundException(id: String) : OrderSessionException(
    status = HttpStatusCode.NotFound,
    errorCode = OrderSessionErrorCode.ORDER_SESSION_NOT_FOUND,
    message = "Order session '$id' was not found.",
)

internal class OrderSessionInvalidPageTokenException : OrderSessionException(
    status = HttpStatusCode.BadRequest,
    errorCode = OrderSessionErrorCode.INVALID_PAGE_TOKEN,
    message = "nextToken is invalid.",
)

internal class DynamoDbUnavailableException(message: String) : OrderSessionException(
    status = HttpStatusCode.ServiceUnavailable,
    errorCode = OrderSessionErrorCode.DYNAMODB_UNAVAILABLE,
    message = message,
)

internal fun OrderSessionException.toErrorResponse(): ErrorResponse =
    ErrorResponse(code = errorCode.code, message = message)
