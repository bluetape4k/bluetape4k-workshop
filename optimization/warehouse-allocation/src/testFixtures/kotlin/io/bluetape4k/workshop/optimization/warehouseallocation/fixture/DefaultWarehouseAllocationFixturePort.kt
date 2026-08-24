package io.bluetape4k.workshop.optimization.warehouseallocation.fixture

import java.security.MessageDigest

public class DefaultWarehouseAllocationFixturePort : WarehouseAllocationFixturePort {
    private var datasetId: String = "dataset-0"
    private var version: Long = 0
    private val events = linkedSetOf<String>()

    override fun reset(seed: Long): String {
        datasetId = "dataset-${seed.toString(16)}"
        version = 0
        events.clear()
        return datasetId
    }

    override fun ingest(canonicalEvent: String): String {
        require(canonicalEvent.toByteArray().size <= 256 * 1024) { "event body too large" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalEvent.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!events.add(digest)) return "duplicate:$digest"
        version++
        return "accepted:$digest"
    }

    override fun snapshot(datasetId: String): String {
        require(datasetId == this.datasetId) { "unknown dataset" }
        return "{\"datasetId\":\"$datasetId\",\"version\":$version,\"reservations\":[]}"
    }
}
