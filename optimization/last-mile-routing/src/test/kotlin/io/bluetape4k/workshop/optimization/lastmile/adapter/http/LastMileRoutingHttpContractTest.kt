package io.bluetape4k.workshop.optimization.lastmile.adapter.http

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LastMileRoutingHttpContractTest {

    companion object: KLogging()

    @Test
    fun `controller keeps bounded redacted endpoint surface`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/io/bluetape4k/workshop/optimization/lastmile/adapter/http/LastMileRoutingController.kt"),
        )
        listOf(
            "/plans/{planId}",
            "/replans",
            "/plans/{planId}/approve",
            "/providers/{provider}/callbacks",
            "/events",
            "/drivers/{driverId}/reconnect",
        ).forEach { mapping ->
            source shouldContain mapping
        }
        source shouldContain "eTag"
        source shouldContain "Idempotency-Key"
    }
}
