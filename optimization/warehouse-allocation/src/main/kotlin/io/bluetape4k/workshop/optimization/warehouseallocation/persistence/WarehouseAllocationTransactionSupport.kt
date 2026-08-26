package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import org.jetbrains.exposed.v1.jdbc.transactions.transaction

internal object WarehouseAllocationTransactionSupport {
    fun <T> inTransaction(block: () -> T): T = transaction { block() }
}
