package io.bluetape4k.workshop.messaging.outbox.domain

import io.bluetape4k.workshop.messaging.outbox.api.OrderResponse
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toOrderResponse() =
    OrderResponse(
        id = this[OrderTable.id].value,
        customerId = this[OrderTable.customerId],
        product = this[OrderTable.product],
        quantity = this[OrderTable.quantity],
        status = this[OrderTable.status],
        createdAt = this[OrderTable.createdAt],
        updatedAt = this[OrderTable.updatedAt],
    )
