package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import org.jetbrains.exposed.v1.jdbc.transactions.transaction

internal object WarehouseAllocationTransactionSupport {

    inline fun <T> inTransaction(crossinline block: () -> T): T =
        transaction { block() }
    
}
