package io.bluetape4k.workshop.commerce.ticket.payment.internal

import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import java.io.Serial
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun interface PaymentProvider {
    fun authorize(operationId: UUID): PaymentOutcome

    fun lookup(operationId: UUID): PaymentOutcome? = null
}

class PaymentTimeout : IllegalStateException("payment_timeout") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Deterministic provider that deduplicates effects by stable operation ID. */
class FakePaymentProvider : PaymentProvider {
    private val outcomes = ConcurrentHashMap<UUID, PaymentOutcome>()
    private val calls = ConcurrentHashMap<UUID, AtomicInteger>()
    private val timeouts = ConcurrentHashMap.newKeySet<UUID>()
    private val responseLosses = ConcurrentHashMap<UUID, PaymentOutcome>()

    override fun authorize(operationId: UUID): PaymentOutcome {
        calls.computeIfAbsent(operationId) { AtomicInteger() }.incrementAndGet()
        if (operationId in timeouts) throw PaymentTimeout()
        responseLosses.remove(operationId)?.let { outcome ->
            outcomes[operationId] = outcome
            throw PaymentTimeout()
        }
        return outcomes.computeIfAbsent(operationId) { PaymentOutcome.APPROVED }
    }

    override fun lookup(operationId: UUID): PaymentOutcome? = outcomes[operationId]

    fun timeout(operationId: UUID) {
        timeouts += operationId
    }

    fun complete(
        operationId: UUID,
        outcome: PaymentOutcome,
    ) {
        timeouts -= operationId
        outcomes[operationId] = outcome
    }

    fun completeButLoseResponse(
        operationId: UUID,
        outcome: PaymentOutcome,
    ) {
        responseLosses[operationId] = outcome
    }

    fun authorizationCount(operationId: UUID): Int = calls[operationId]?.get() ?: 0
}
