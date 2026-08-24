package io.bluetape4k.workshop.optimization.warehouseallocation.web

import io.bluetape4k.workshop.optimization.warehouseallocation.adapter.http.WarehouseAllocationHttpService
import io.bluetape4k.workshop.optimization.warehouseallocation.adapter.http.WarehouseAllocationSignatureVerifier
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationErrorCode
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/planning")
internal class PlanningContractsController(
    private val service: WarehouseAllocationHttpService,
    private val verifier: WarehouseAllocationSignatureVerifier,
) {
    @PostMapping("/requests")
    fun request(@RequestBody request: PlanningRequestDto): ResponseEntity<PlanningRequestResponse> = ResponseEntity.accepted().body(service.createPlanningRequest(request))

    @PostMapping("/callbacks/{provider}")
    fun callback(
        @PathVariable provider: String,
        @RequestBody request: PlanningCallbackDto,
        @RequestHeader("X-Planning-Signature", required = false) signature: String?,
    ): PlanningCallbackResponse {
        if (provider.equals("FAKE", ignoreCase = true) && !verifier.verifyFake(signature)) return PlanningCallbackResponse("REJECTED")
        return service.callback(provider, request)
    }

    @GetMapping("/requests/{requestId}")
    fun query(@PathVariable requestId: String): PlanningQueryResponse = service.queryPlanningRequest(requestId)
        ?: throw WarehouseAllocationException(WarehouseAllocationErrorCode.UNKNOWN_TARGET, "unknown planning request")
}
