package io.bluetape4k.workshop.optimization.lastmile.adapter.http

import io.bluetape4k.assertions.shouldBeTrue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class LastMileRoutingHttpContractTest {
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
        ).forEach { mapping -> source.contains(mapping).shouldBeTrue() }
        source.contains("eTag").shouldBeTrue()
        source.contains("Idempotency-Key").shouldBeTrue()
    }
}
