package io.bluetape4k.workshop.optimization.warehouseallocation.application

import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal enum class WarehouseAllocationDeliveryResult { DELIVERED, DELIVERY_UNKNOWN, RETRYABLE }

@Service
internal class WarehouseAllocationOutboxWorker(
    private val repository: WarehouseAllocationRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val accepting = AtomicBoolean(true)

    fun deliver(operationKey: String, result: WarehouseAllocationDeliveryResult): Boolean = transaction {
        if (!accepting.get()) {
            false
        } else {
            val owner = "worker-${Thread.currentThread().name}"
            val token = UUID.randomUUID().toString()
            if (!repository.claimOutbox(operationKey, owner, token, clock.instant())) {
                false
            } else {
                when (result) {
                    WarehouseAllocationDeliveryResult.DELIVERED -> repository.completeOutbox(operationKey, owner, token, true)
                    WarehouseAllocationDeliveryResult.DELIVERY_UNKNOWN -> repository.completeOutbox(operationKey, owner, token, false)
                    WarehouseAllocationDeliveryResult.RETRYABLE -> repository.markRetryable(operationKey, owner, token)
                }
            }
        }
    }

    fun closeAdmission() { accepting.set(false) }
    fun isAccepting(): Boolean = accepting.get()
}
