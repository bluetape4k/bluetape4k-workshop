package io.bluetape4k.workshop.optimization.warehouseallocation

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class WarehouseAllocationRuntimeContractTest {
    @Test
    fun `application and loopback resource contract exist`() {
        WarehouseAllocationApplication::class.java.name.endsWith("WarehouseAllocationApplication").shouldBeTrue()
        val resource = Files.readString(Path.of("src/main/resources/application.yml"))
        resource.contains("address: 127.0.0.1").shouldBeTrue()
        resource.contains("WAREHOUSE_ALLOCATION_SERVER_PORT:8080").shouldBeTrue()
        resource.contains("provider: fake").shouldBeTrue()
    }
}
