package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.exposed.jdbc.repository.JdbcRepository
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class WarehouseAllocationRepositoryArchitectureTest {

    companion object: KLogging()

    @Test
    fun `warehouse allocation persistence uses bluetape4k exposed jdbc repository`() {
        WarehouseAllocationRepository().shouldBeInstanceOf<JdbcRepository<*, *>>()
    }
}
