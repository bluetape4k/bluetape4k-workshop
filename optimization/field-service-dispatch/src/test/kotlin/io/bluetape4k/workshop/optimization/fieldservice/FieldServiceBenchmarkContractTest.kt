package io.bluetape4k.workshop.optimization.fieldservice

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class FieldServiceBenchmarkContractTest {
    @Test
    fun `max envelope probe writes passing invariant report`() {
        val output = FieldServiceBenchmarkProbe.run(Path.of("build/reports/field-service/benchmark.json"))
        Files.exists(output).shouldBeTrue()
        val report = Files.readString(output)
        report shouldContain "\"schemaVersion\": 1"
        report shouldContain "\"warmup\": 2"
        report shouldContain "\"repetitions\": 5"
        report shouldContain "\"complexity\": \"O(V*W+E)\""
        report shouldContain "\"lockWaitMs\": null"
        report shouldContain "\"notModifiedRatio\": null"
        report shouldContain "\"status\": \"PASS\""
    }
}
