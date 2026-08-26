package io.bluetape4k.workshop.optimization.warehouseallocation.web

import io.bluetape4k.workshop.optimization.warehouseallocation.adapter.http.WarehouseAllocationHttpService
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationApprovalService
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationApprovalResult
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationCommandService
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationOrderService
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationPinService
import io.bluetape4k.workshop.optimization.warehouseallocation.application.WarehouseAllocationReplanService
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import io.bluetape4k.workshop.optimization.warehouseallocation.persistence.WarehouseAllocationRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Profile("demo")
@RestController
@RequestMapping("/api/warehouse-allocation")
internal class WarehouseAllocationMutationController(
    private val http: WarehouseAllocationHttpService,
    private val command: WarehouseAllocationCommandService,
    private val approval: WarehouseAllocationApprovalService,
    private val replan: WarehouseAllocationReplanService,
    private val pins: WarehouseAllocationPinService,
    private val orders: WarehouseAllocationOrderService,
    private val repository: WarehouseAllocationRepository,
) {
    @PostMapping("/events")
    fun event(
        @RequestBody request: WarehouseAllocationEventRequest,
        @RequestHeader("X-Demo-Operator") demo: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("X-Request-Id") requestId: String,
    ): ResponseEntity<Any> {
        requireOperator(demo, key, requestId)
        val result = http.ingest(request, requestId)
        return ResponseEntity.accepted().body(mapOf("operationKey" to result.operationKey, "requestId" to requestId, "state" to result.state))
    }

    @PostMapping("/replans")
    fun replan(
        @RequestBody request: WarehouseAllocationReplanRequest,
        @RequestHeader("X-Demo-Operator") demo: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("X-Request-Id") requestId: String,
    ): ResponseEntity<Any> {
        requireOperator(demo, key, requestId)
        val generation = request.parentPlanRevision ?: transaction { repository.nextReplanGeneration(request.datasetId) }
        val queued = replan.queue(request.datasetId, generation, requestId)
        val alreadyMaterialized = transaction { repository.outbox(queued.operationKey) != null }
        if (!alreadyMaterialized && queued.requestId == requestId) {
            val planned = command.createPlanFromCurrentSnapshot(request.datasetId, request.seed, requestId, queued.operationKey)
            transaction {
                repository.markReplanMaterialized(request.datasetId, generation, planned.proposal.planId.value, planned.proposal.datasetVersion)
            }
        }
        return ResponseEntity.accepted().body(queued)
    }

    @PostMapping("/plans/{planId}/approve")
    fun approve(
        @PathVariable planId: String,
        @RequestBody request: WarehouseAllocationApproveRequest,
        @RequestHeader("X-Demo-Operator") demo: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestHeader("X-Request-Id") requestId: String,
    ): WarehouseAllocationApprovalResult {
        requireOperator(demo, key, requestId)
        return approval.approve(planId, request.expectedPlanRevision, requestId)
    }

    @PostMapping("/plans/{planId}/reject")
    fun reject(@PathVariable planId: String, @RequestBody request: WarehouseAllocationRejectRequest, @RequestHeader("X-Demo-Operator") demo: String, @RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Request-Id") requestId: String): WarehouseAllocationApprovalResult {
        requireOperator(demo, key, requestId)
        return approval.reject(planId, request.expectedPlanRevision, request.reasonCode, requestId)
    }

    @PostMapping("/pins")
    fun pin(@RequestBody request: WarehouseAllocationPinRequest, @RequestHeader("X-Demo-Operator") demo: String, @RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Request-Id") requestId: String) = run {
        requireOperator(demo, key, requestId)
        pins.create(request.lineId, request.warehouseId, request.quantity, request.expectedLineRevision, "demo-operator", requestId)
    }

    @DeleteMapping("/pins/{pinId}")
    fun removePin(@PathVariable pinId: String, @RequestParam(defaultValue = "0") expectedRevision: Long, @RequestHeader("X-Demo-Operator") demo: String, @RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Request-Id") requestId: String) = run {
        requireOperator(demo, key, requestId)
        pins.remove(pinId, expectedRevision, requestId)
    }

    @PostMapping("/orders/{orderId}/cancel")
    fun cancel(@PathVariable orderId: String, @RequestBody request: WarehouseAllocationCancelRequest, @RequestHeader("X-Demo-Operator") demo: String, @RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Request-Id") requestId: String) = run {
        requireOperator(demo, key, requestId)
        orders.cancel(orderId, request.expectedOrderRevision, requestId)
    }

    @PostMapping("/outbox/{operationKey}/redrive")
    fun redrive(@PathVariable operationKey: String, @RequestHeader("X-Demo-Operator") demo: String, @RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Request-Id") requestId: String): ResponseEntity<Any> {
        requireOperator(demo, key, requestId)
        org.jetbrains.exposed.v1.jdbc.transactions.transaction { repository.redriveOutbox(operationKey) }
        return ResponseEntity.accepted().body(mapOf("operationKey" to operationKey, "requestId" to requestId, "state" to "RETRYABLE"))
    }

    private fun requireOperator(demo: String, key: String, requestId: String) {
        require(demo == "true") { "demo operator header is required" }
        if (key.length !in 1..128 || !key.matches(Regex("[A-Za-z0-9._-]+"))) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST, "invalid idempotency key")
        }
        if (requestId.length !in 1..128 || !requestId.matches(Regex("[A-Za-z0-9._-]+"))) {
            throw WarehouseAllocationException(WarehouseAllocationErrorCode.INVALID_REQUEST_ID, "invalid request id")
        }
    }
}
