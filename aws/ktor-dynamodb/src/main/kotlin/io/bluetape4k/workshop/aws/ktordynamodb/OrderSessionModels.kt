package io.bluetape4k.workshop.aws.ktordynamodb

import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

internal const val ORDER_SESSION_DEFAULT_LIST_LIMIT: Int = 25
internal const val ORDER_SESSION_MAX_LIST_LIMIT: Int = 100
internal const val ORDER_SESSION_REQUEST_BODY_LIMIT_BYTES: Long = 64L * 1024L

@Serializable
internal enum class OrderSessionStatus {
    CREATED,
    APPROVED,
    CANCELLED,
}

@Serializable
internal data class CreateOrderSessionRequest(
    val id: String,
    val customerId: String,
    val status: OrderSessionStatus = OrderSessionStatus.CREATED,
    val notes: String = "",
) : JavaSerializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Serializable
internal data class UpdateOrderSessionRequest(
    val expectedVersion: Long,
    val status: OrderSessionStatus,
    val notes: String = "",
) : JavaSerializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Serializable
internal data class OrderSessionResponse(
    val id: String,
    val customerId: String,
    val status: OrderSessionStatus,
    val notes: String,
    val version: Long,
) : JavaSerializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Serializable
internal data class OrderSessionListResponse(
    val items: List<OrderSessionResponse>,
    val nextToken: String? = null,
) : JavaSerializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Serializable
internal data class ErrorResponse(
    val code: String,
    val message: String,
) : JavaSerializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Serializable
internal data class ReadinessResponse(
    val status: String,
    val mode: String,
    val emulator: String?,
    val region: String,
    val tableName: String,
    val tableReady: Boolean,
    val checkedAt: String,
) : JavaSerializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class OrderSession(
    val id: String,
    val customerId: String,
    val status: OrderSessionStatus,
    val notes: String,
    val version: Long,
) : JavaSerializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
