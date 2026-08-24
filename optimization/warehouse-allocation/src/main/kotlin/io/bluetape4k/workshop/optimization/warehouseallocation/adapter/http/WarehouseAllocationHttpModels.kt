package io.bluetape4k.workshop.optimization.warehouseallocation.adapter.http

internal data class WarehouseAllocationPlanningRequestState(
    val id: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val datasetId: String,
    val provider: String,
    var status: String = "QUEUED",
    var providerRevision: Long? = null,
    var scoreSummary: String? = null,
    var explanations: List<String> = emptyList(),
)
