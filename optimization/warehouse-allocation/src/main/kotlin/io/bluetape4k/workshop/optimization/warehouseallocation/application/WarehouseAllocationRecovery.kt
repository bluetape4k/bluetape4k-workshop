package io.bluetape4k.workshop.optimization.warehouseallocation.application

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.OutboxState
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service
import java.time.Clock

@Service
internal class WarehouseAllocationRecovery(
    private val repository: WarehouseAllocationRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun recover(operationKeys: List<String>, batchSize: Int = 20): Int {
        var recovered = 0
        transaction {
            operationKeys.sorted().take(batchSize.coerceIn(1, 100)).forEach { key ->
            val row = repository.outbox(key) ?: return@forEach
            if (row.status == OutboxState.CLAIMED && row.leaseExpiresAt?.isBefore(clock.instant()) == true) {
                if (repository.markRetryable(key, row.leaseOwner ?: "recovery", row.leaseToken ?: "expired")) recovered++
            }
            }
        }
        return recovered
    }
}
