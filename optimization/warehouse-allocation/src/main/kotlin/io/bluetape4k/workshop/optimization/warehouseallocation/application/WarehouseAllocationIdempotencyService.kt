package io.bluetape4k.workshop.optimization.warehouseallocation.application

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationIdempotencyRecord
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import org.springframework.stereotype.Service
import java.time.Instant

@Service
internal class WarehouseAllocationIdempotencyService(
    private val repository: WarehouseAllocationRepository,
) {
    fun claim(method: String, route: String, demoScope: String, key: String, fingerprint: String, target: String): WarehouseAllocationIdempotencyRecord =
        repository.claimIdempotency(method, route, demoScope, key, fingerprint, target)
            ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "idempotency claim failed")

    fun complete(record: WarehouseAllocationIdempotencyRecord, response: String) {
        repository.updateIdempotency(record.id, "COMPLETED", response)
    }

    fun retryable(record: WarehouseAllocationIdempotencyRecord, response: String, nextRetryAt: Instant) {
        repository.updateIdempotency(record.id, "RETRYABLE", response, attempt = record.attempt + 1, nextRetryAt = nextRetryAt)
    }

    fun terminal(record: WarehouseAllocationIdempotencyRecord, response: String) {
        repository.updateIdempotency(record.id, "FAILED_TERMINAL", response, attempt = record.attempt + 1)
    }
}
