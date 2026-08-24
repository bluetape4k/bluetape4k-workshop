package io.bluetape4k.workshop.optimization.warehouseallocation

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class WarehouseAllocationRuntimeContractTest {
    @Test
    fun `application and loopback resource contract exist`() {
        assertTrue(WarehouseAllocationApplication::class.java.name.endsWith("WarehouseAllocationApplication"))
        val resource = Files.readString(Path.of("src/main/resources/application.yml"))
        assertTrue(resource.contains("address: 127.0.0.1"))
        assertTrue(resource.contains("WAREHOUSE_ALLOCATION_SERVER_PORT:8080"))
        assertTrue(resource.contains("provider: fake"))
    }
}
