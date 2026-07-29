package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.workshop.commerce.ticket.operations.api.OperationsCommands
import io.bluetape4k.workshop.commerce.ticket.operations.api.OperatorReconcile
import io.bluetape4k.workshop.commerce.ticket.operations.api.ReconcileSummary
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serial
import java.io.Serializable
import java.time.Clock
import io.bluetape4k.idgenerators.uuid.Uuid

data class OperatorReconcileRequest(
    @field:Min(1) @field:Max(50) val limit: Int,
    @field:Size(min = 8, max = 200) val reason: String,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** bounded operator recovery API입니다. authorization은 [TicketSecurityConfiguration]이 강제합니다. */
@RestController
@RequestMapping("/api/v1/operator")
@ConditionalOnBean(OperationsCommands::class)
class OperatorTicketController(
    private val operations: OperationsCommands,
    private val clock: Clock,
) {
    @PostMapping("/reconciliation-runs")
    fun reconcile(@Valid @RequestBody request: OperatorReconcileRequest): ResponseEntity<ReconcileSummary> =
        ResponseEntity.accepted().body(
            operations.reconcile(OperatorReconcile(Uuid.V7.nextId(), request.limit, clock.instant(), request.reason.trim())),
        )
}
