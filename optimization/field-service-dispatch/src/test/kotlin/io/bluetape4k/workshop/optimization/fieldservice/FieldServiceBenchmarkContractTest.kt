package io.bluetape4k.workshop.optimization.fieldservice

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class FieldServiceBenchmarkContractTest {
    @Test
    fun `max envelope probe writes passing invariant report`() {
        val output = FieldServiceBenchmarkProbe.run(Path.of("build/reports/field-service/benchmark.json"))
        Files.exists(output) shouldBeEqualTo true
        val report = Files.readString(output)
        check("\"schemaVersion\": 1" in report)
        check("\"warmup\": 2" in report)
        check("\"repetitions\": 5" in report)
        check("\"complexity\": \"O(V*W+E)\"" in report)
        check("\"lockWaitMs\": null" in report)
        check("\"notModifiedRatio\": null" in report)
        check("\"status\": \"PASS\"" in report)
    }
}
