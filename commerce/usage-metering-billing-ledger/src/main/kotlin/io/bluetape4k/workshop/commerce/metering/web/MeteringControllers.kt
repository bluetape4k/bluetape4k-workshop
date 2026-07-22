package io.bluetape4k.workshop.commerce.metering.web

import io.bluetape4k.workshop.commerce.metering.application.AdjustmentService
import io.bluetape4k.workshop.commerce.metering.application.BillingCloseService
import io.bluetape4k.workshop.commerce.metering.application.BillingPeriodService
import io.bluetape4k.workshop.commerce.metering.application.InvoiceService
import io.bluetape4k.workshop.commerce.metering.application.MeterService
import io.bluetape4k.workshop.commerce.metering.application.PriceActivationService
import io.bluetape4k.workshop.commerce.metering.application.PriceGapRepair
import io.bluetape4k.workshop.commerce.metering.application.ReconciliationService
import io.bluetape4k.workshop.commerce.metering.application.UsageIngestionCommand
import io.bluetape4k.workshop.commerce.metering.application.UsageIngestionService
import io.bluetape4k.workshop.commerce.metering.config.MeteringMetrics
import io.bluetape4k.workshop.commerce.metering.domain.IdempotencyKey
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.SourceEventId
import io.bluetape4k.workshop.commerce.metering.domain.SourceSystem
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.domain.UnitPrice
import io.bluetape4k.workshop.commerce.metering.domain.UsageQuantity
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandAcquireResult
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptScope
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptService
import io.bluetape4k.workshop.commerce.metering.idempotency.Sha256Digest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.util.Currency
import java.util.UUID

data class IdempotentHttpCommand(
    val tenantId: String,
    val operation: String,
    val rawKey: String,
    val fingerprint: Sha256Digest,
)

@Component
class IdempotentHttpCommandExecutor(
    private val receipts: CommandReceiptService,
    private val objectMapper: ObjectMapper,
    private val metrics: MeteringMetrics,
) {
    fun execute(request: IdempotentHttpCommand, command: () -> Any): ResponseEntity<Any> {
        val scope = CommandReceiptScope(
            request.tenantId,
            request.operation,
            CommandFingerprint.key(IdempotencyKey(request.rawKey).value).value,
        )
        return when (val acquired = receipts.acquire(scope, request.fingerprint)) {
            is CommandAcquireResult.Acquired -> executeOwned(request.operation, acquired, command)
            is CommandAcquireResult.Replay -> meter(request.operation, "replay") {
                ResponseEntity.status(acquired.httpStatus)
                    .header("Idempotency-Replayed", "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(acquired.response)
            }
            is CommandAcquireResult.InProgress -> meter(request.operation, "in_progress") {
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .header(HttpHeaders.RETRY_AFTER, acquired.retryAfter.seconds.coerceAtLeast(1).toString())
                    .body(ApiError("command_in_progress", "command_in_progress"))
            }
            CommandAcquireResult.Conflict -> meter(request.operation, "conflict") {
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiError("idempotency_conflict", "idempotency_conflict"))
            }
        }
    }

    private fun executeOwned(
        operation: String,
        acquired: CommandAcquireResult.Acquired,
        command: () -> Any,
    ): ResponseEntity<Any> {
        try {
            val result = command()
            val body = objectMapper.writeValueAsString(result)
            check(
                receipts.succeed(
                    acquired.receipt.id,
                    acquired.receipt.ownerToken,
                    HttpStatus.CREATED.value(),
                    body,
                ),
            ) { "command_receipt_owner_lost" }
            metrics.command(operation, "created")
            return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(result)
        } catch (failure: IllegalArgumentException) {
            recordFailure(acquired, failure)
            metrics.command(operation, "rejected")
            throw failure
        } catch (failure: IllegalStateException) {
            recordFailure(acquired, failure)
            metrics.command(operation, "rejected")
            throw failure
        }
    }

    private fun meter(operation: String, result: String, response: () -> ResponseEntity<Any>): ResponseEntity<Any> {
        metrics.command(operation, result)
        return response()
    }

    private fun recordFailure(acquired: CommandAcquireResult.Acquired, failure: RuntimeException) {
        val message = failure.message ?: "command_failed"
        receipts.fail(
            acquired.receipt.id,
            acquired.receipt.ownerToken,
            MeteringErrorStatus.from(failure).value(),
            objectMapper.writeValueAsString(ApiError(message, message)),
        )
    }
}

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
class TenantMeteringController(
    private val commands: IdempotentHttpCommandExecutor,
    private val meters: MeterService,
    private val prices: PriceActivationService,
    private val usage: UsageIngestionService,
    private val periods: BillingPeriodService,
) {
    @PostMapping("/meters")
    fun registerMeter(
        @PathVariable tenantId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: RegisterMeterRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> = execute(
        IdempotentHttpCommand(
            tenantId,
            "meter-register",
            key,
            CommandFingerprint.request("meter-register", mapOf("code" to request.code, "unit" to request.unit)),
        ),
        authentication,
    ) { meters.register(TenantId(tenantId), MeterCode(request.code), request.unit, request.description) }

    @PostMapping("/meters/{meterCode}/prices")
    fun activatePrice(
        @PathVariable tenantId: String,
        @PathVariable meterCode: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: ActivatePriceRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> = execute(
        IdempotentHttpCommand(
            tenantId,
            "price-activate",
            key,
            CommandFingerprint.request(
                "price-activate",
                mapOf(
                    "meterCode" to meterCode,
                    "currency" to request.currency,
                    "unitPrice" to CommandFingerprint.decimal(request.unitPrice),
                    "effectiveFrom" to CommandFingerprint.instant(request.effectiveFrom),
                ),
            ),
        ),
        authentication,
    ) {
        prices.activate(
            TenantId(tenantId), MeterCode(meterCode), Currency.getInstance(request.currency),
            UnitPrice(request.unitPrice), request.effectiveFrom,
        )
    }

    @PostMapping("/usage-events")
    fun ingest(
        @PathVariable tenantId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: IngestUsageRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val fingerprint = CommandFingerprint.request(
            "usage-ingest",
            mapOf(
                "sourceSystem" to request.sourceSystem,
                "sourceEventId" to request.sourceEventId,
                "meterCode" to request.meterCode,
                "quantity" to CommandFingerprint.decimal(request.quantity),
                "occurredAt" to CommandFingerprint.instant(request.occurredAt),
            ),
        )
        return execute(IdempotentHttpCommand(tenantId, "usage-ingest", key, fingerprint), authentication) {
            usage.ingest(
                UsageIngestionCommand(
                    tenantId = TenantId(tenantId),
                    sourceSystem = SourceSystem(request.sourceSystem),
                    sourceEventId = SourceEventId(request.sourceEventId),
                    meterCode = MeterCode(request.meterCode),
                    quantity = UsageQuantity(request.quantity),
                    occurredAt = request.occurredAt,
                    actor = authentication.name,
                    correlationId = request.correlationId,
                    requestFingerprint = fingerprint,
                ),
            )
        }
    }

    @PostMapping("/billing-periods")
    fun createPeriod(
        @PathVariable tenantId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: CreateBillingPeriodRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> = execute(
        IdempotentHttpCommand(
            tenantId,
            "period-create",
            key,
            CommandFingerprint.request(
                "period-create",
                mapOf(
                    "currency" to request.currency,
                    "startsAt" to request.startsAt.toString(),
                    "endsAt" to request.endsAt.toString(),
                ),
            ),
        ),
        authentication,
    ) { periods.create(TenantId(tenantId), Currency.getInstance(request.currency), request.startsAt, request.endsAt) }

    private fun execute(
        request: IdempotentHttpCommand,
        authentication: Authentication,
        command: () -> Any,
    ): ResponseEntity<Any> {
        requireTenant(authentication, request.tenantId)
        return commands.execute(request, command)
    }
}

@RestController
@RequestMapping("/api/v1/operator/tenants/{tenantId}")
@Suppress("LongParameterList") // Operator routes expose explicit application boundaries without a service locator.
class MeteringOperatorController(
    private val periods: BillingPeriodService,
    private val close: BillingCloseService,
    private val invoices: InvoiceService,
    private val adjustments: AdjustmentService,
    private val reconciliation: ReconciliationService,
    private val prices: PriceActivationService,
    private val commands: IdempotentHttpCommandExecutor,
) {
    @PostMapping("/billing-periods/{periodId}/close-runs")
    fun start(@PathVariable tenantId: String, @PathVariable periodId: UUID): ResponseEntity<Any> {
        val run = periods.startClose(TenantId(tenantId), periodId)
        return ResponseEntity.accepted()
            .header(HttpHeaders.LOCATION, "/api/v1/operator/tenants/$tenantId/close-runs/${run.id}")
            .body(run)
    }

    @PostMapping("/close-runs/{runId}/process-next")
    fun process(
        @PathVariable tenantId: String,
        @PathVariable runId: UUID,
    ): Any = close.processNextBatch(tenantId, runId)

    @PostMapping("/close-runs/{runId}/resume-after-price-repair")
    fun resume(@PathVariable tenantId: String, @PathVariable runId: UUID): Any =
        close.resumeAfterPriceRepair(tenantId, runId)

    @PostMapping("/close-runs/{runId}/finalize")
    fun finalize(@PathVariable tenantId: String, @PathVariable runId: UUID): Any = invoices.finalize(tenantId, runId)

    @PostMapping("/usage-events/{usageEventId}/late-debit")
    fun lateDebit(
        @PathVariable tenantId: String,
        @PathVariable usageEventId: UUID,
        @RequestHeader("X-Currency") currency: String,
    ): Map<String, UUID> = mapOf("ledgerEntryId" to adjustments.postLateDebit(tenantId, usageEventId, currency))

    @PostMapping("/adjustments/credits")
    fun credit(
        @PathVariable tenantId: String,
        @RequestBody request: CreditAdjustmentRequest,
        authentication: Authentication,
    ): Map<String, UUID> {
        val entryId = adjustments.postCredit(
            tenantId,
            UUID.fromString(request.originalEntryId),
            request.reason,
            authentication.name,
        )
        return mapOf("ledgerEntryId" to entryId)
    }

    @PostMapping("/reconciliation-runs")
    fun reconcile(@PathVariable tenantId: String): Any = reconciliation.inspect(tenantId)

    @PostMapping("/reconciliation-findings/{findingId}/repair-late-usage")
    fun repairLateUsage(
        @PathVariable tenantId: String,
        @PathVariable findingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: ReconciliationRepairRequest,
    ): ResponseEntity<Any> {
        val fingerprint = CommandFingerprint.request(
            "reconciliation-repair",
            mapOf(
                "findingId" to findingId.toString(),
                "expectedDigest" to request.expectedDigest,
                "currency" to request.currency,
            ),
        )
        return commands.execute(
            IdempotentHttpCommand(tenantId, "reconciliation-repair", key, fingerprint),
        ) {
            mapOf(
                "ledgerEntryId" to reconciliation.repairLateUsage(
                    tenantId,
                    findingId,
                    request.expectedDigest,
                    request.currency,
                ),
            )
        }
    }

    @PostMapping("/price-gaps/repair")
    fun repairPriceGap(
        @PathVariable tenantId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: RepairPriceGapRequest,
    ): ResponseEntity<Any> {
        val fingerprint = CommandFingerprint.request(
            "price-gap-repair",
            mapOf(
                "meterCode" to request.meterCode,
                "currency" to request.currency,
                "unitPrice" to CommandFingerprint.decimal(request.unitPrice),
                "effectiveFrom" to request.effectiveFrom.toString(),
                "effectiveTo" to request.effectiveTo.toString(),
            ),
        )
        return commands.execute(IdempotentHttpCommand(tenantId, "price-gap-repair", key, fingerprint)) {
            prices.repairGap(
                PriceGapRepair(
                    TenantId(tenantId),
                    MeterCode(request.meterCode),
                    Currency.getInstance(request.currency),
                    UnitPrice(request.unitPrice),
                    request.effectiveFrom,
                    request.effectiveTo,
                ),
            )
        }
    }
}

private fun requireTenant(authentication: Authentication, tenantId: String) {
    val operator = authentication.authorities.any { it.authority == "ROLE_OPERATOR" }
    require(operator || authentication.name == tenantId) { "tenant_mismatch" }
}
