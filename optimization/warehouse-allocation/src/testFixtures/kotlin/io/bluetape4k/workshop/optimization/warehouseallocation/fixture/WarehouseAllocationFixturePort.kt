package io.bluetape4k.workshop.optimization.warehouseallocation.fixture

public interface WarehouseAllocationFixturePort {
    public fun reset(seed: Long): String
    public fun ingest(canonicalEvent: String): String
    public fun snapshot(datasetId: String): String
}
