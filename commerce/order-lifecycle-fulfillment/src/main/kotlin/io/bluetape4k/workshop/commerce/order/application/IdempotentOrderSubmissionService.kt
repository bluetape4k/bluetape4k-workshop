package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrder
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrderLine
import io.bluetape4k.workshop.commerce.order.idempotency.AcquireResult
import io.bluetape4k.workshop.commerce.order.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.order.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.order.idempotency.IdempotencyScope
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class SubmitOrderRequest(
    val tenantId: String,
    val customerReference: String,
    val lines: List<SubmitOrderLineRequest>,
    val providerMode: ProviderMode = ProviderMode.SUCCESS,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class SubmitOrderLineRequest(
    val sku: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class IdempotentOrderResult(
    val status: HttpStatus,
    val body: String,
    val replayed: Boolean,
    val retryAfterSeconds: Long? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Service
internal class IdempotentOrderSubmissionService(
    private val idempotency: HttpIdempotencyRepository,
    private val commands: OrderCommandService,
    private val clock: Clock,
) {
    @Transactional
    fun submit(
        rawKey: String,
        request: SubmitOrderRequest,
    ): IdempotentOrderResult {
        require(rawKey.length in 8..200) { "Idempotency-Key must contain 8..200 characters" }
        val now = Instant.now(clock)
        val scope =
            IdempotencyScope(
                tenantId = request.tenantId,
                operation = SUBMIT_ORDER,
                keyHash = IdempotencyFingerprint.key(rawKey)
            )
        val ownerToken = Uuid.V7.nextId()
        val acquired =
            idempotency.acquire(
                scope = scope,
                fingerprint = IdempotencyFingerprint.request(request.canonicalPayload()),
                ownerToken = ownerToken,
                now = now,
                lease = LEASE,
                retention = RETENTION
            )

        return when (acquired) {
            is AcquireResult.Acquired -> {
                val submitted = commands.submit(request.toCommand())
                val body = """{"orderId":"${submitted.orderId}","paymentAttemptId":"${submitted.paymentAttemptId}"}"""
                check(idempotency.finalize(acquired.record.id, ownerToken, HttpStatus.CREATED.value(), body, false)) {
                    "idempotency owner lost before finalize"
                }
                log.info {
                    "idempotency_completed scope=$SUBMIT_ORDER keyHashPrefix=${scope.keyHash.take(12)} " +
                        "orderId=${submitted.orderId} status=${HttpStatus.CREATED.value()}"
                }
                IdempotentOrderResult(HttpStatus.CREATED, body, replayed = false)
            }
            is AcquireResult.Replay -> {
                log.info {
                    "idempotency_replayed scope=$SUBMIT_ORDER keyHashPrefix=${scope.keyHash.take(
                        12
                    )} status=${acquired.status}"
                }
                IdempotentOrderResult(
                    status = HttpStatus.valueOf(acquired.status),
                    body = acquired.body,
                    replayed = true
                )
            }
            AcquireResult.FingerprintConflict -> {
                log.warn {
                    "idempotency_fingerprint_conflict scope=$SUBMIT_ORDER keyHashPrefix=${scope.keyHash.take(12)}"
                }
                errorResult(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_FINGERPRINT_CONFLICT"
                )
            }
            is AcquireResult.InProgress -> {
                log.warn {
                    "idempotency_in_progress scope=$SUBMIT_ORDER keyHashPrefix=${scope.keyHash.take(12)} " +
                        "retryAfterSeconds=${acquired.retryAfter.seconds.coerceAtLeast(1)}"
                }
                errorResult(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_IN_PROGRESS",
                    acquired.retryAfter.seconds.coerceAtLeast(1)
                )
            }
        }
    }

    private fun SubmitOrderRequest.toCommand(): SubmitOrder =
        SubmitOrder(
            tenantId = tenantId,
            customerReference = customerReference,
            providerMode = providerMode,
            lines = lines.map { SubmitOrderLine(it.sku, it.quantity, it.unitPrice) }
        )

    private fun SubmitOrderRequest.canonicalPayload(): String =
        buildString {
            append("tenant=")
                .append(tenantId.length)
                .append(':')
                .append(tenantId)
                .append('\n')
            append("customer=")
                .append(customerReference.length)
                .append(':')
                .append(customerReference)
                .append('\n')
            append("provider=").append(providerMode.name).append('\n')
            lines.forEachIndexed { index, line ->
                append(index)
                    .append('|')
                    .append(line.sku.length)
                    .append(':')
                    .append(line.sku)
                    .append('|')
                    .append(line.quantity)
                    .append('|')
                    .append(line.unitPrice.stripTrailingZeros().toPlainString())
                    .append('\n')
            }
        }

    private fun errorResult(
        status: HttpStatus,
        code: String,
        retryAfterSeconds: Long? = null,
    ) = IdempotentOrderResult(
        status = status,
        body = """{"code":"$code"}""",
        replayed = false,
        retryAfterSeconds = retryAfterSeconds
    )

    companion object : KLogging() {
        private const val SUBMIT_ORDER = "SUBMIT_ORDER"
        private val LEASE = Duration.ofSeconds(30)
        private val RETENTION = Duration.ofHours(24)
    }
}
