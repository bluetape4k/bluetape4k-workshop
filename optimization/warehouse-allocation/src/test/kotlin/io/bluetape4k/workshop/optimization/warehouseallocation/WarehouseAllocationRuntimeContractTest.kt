package io.bluetape4k.workshop.optimization.warehouseallocation

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldEndWith
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class WarehouseAllocationRuntimeContractTest {

    companion object: KLogging()

    @Test
    fun `application and loopback resource contract exist`() {
        WarehouseAllocationApplication::class.java.name shouldEndWith "WarehouseAllocationApplication"

        val resource = Files.readString(Path.of("src/main/resources/application.yml"))
        resource shouldContain "address: 127.0.0.1"
        resource shouldContain "WAREHOUSE_ALLOCATION_SERVER_PORT:8080"
        resource shouldContain "provider: fake"
    }
}
