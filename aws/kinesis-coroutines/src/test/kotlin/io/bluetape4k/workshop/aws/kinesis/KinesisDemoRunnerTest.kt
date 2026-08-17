package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.spring.kinesis.KinesisRecordFlowOptions
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

class KinesisDemoRunnerTest {

    @Test
    fun `local runner publishes and consumes three records with redacted summary`() = runSuspendIO {
        val fixture = fixture()

        fixture.runner.run(DefaultApplicationArguments())

        fixture.runner.result?.publishedCount shouldBeEqualTo 3
        fixture.runner.result?.consumedCount shouldBeEqualTo 3
        fixture.runner.result?.sequenceNumbers shouldBeEqualTo listOf("1", "2", "3")
        fixture.runner.result.toString().contains("demo-payload") shouldBeEqualTo false
        fixture.runner.result.toString().contains("secret") shouldBeEqualTo false
        fixture.scope.appJobCount shouldBeEqualTo 0
    }

    @Test
    fun `run demo false does not invoke operations`() = runSuspendIO {
        val operations = LocalKinesisOperations("orders")
        val fixture = fixture(
            properties = KinesisWorkshopProperties(
                streamName = "orders",
                partitionKey = "orders-secret",
                runDemo = false,
            ),
            operations = operations,
        )

        fixture.runner.run(DefaultApplicationArguments())

        fixture.runner.result shouldBeEqualTo null
        operations.getRecordsCalls shouldBeEqualTo 0
    }

    @Test
    fun `collector completion is passively removed from shared registry`() = runSuspendIO {
        val fixture = fixture()
        fixture.service.ensureStream()
        fixture.service.publish(KinesisEvent("one", "orders-secret", 1, "payload"))

        fixture.service.consume().take(1).toList()

        fixture.scope.callerCollectorCount shouldBeEqualTo 0
        fixture.operations.closed shouldBeEqualTo false
    }

    private fun fixture(
        properties: KinesisWorkshopProperties = KinesisWorkshopProperties(
            streamName = "orders",
            partitionKey = "orders-secret",
            batchLimit = 2,
            readinessTimeout = Duration.ofSeconds(1),
            readinessPollInterval = Duration.ofMillis(1),
        ),
        operations: LocalKinesisOperations = LocalKinesisOperations(properties.streamName),
    ): Fixture {
        val scope = KinesisDemoScope()
        val service = KinesisStreamService(
            properties = properties,
            operations = operations,
            objectMapper = Jackson.defaultJsonMapper,
            flowOptions = KinesisRecordFlowOptions(batchLimit = properties.batchLimit),
            demoScope = scope,
        )
        val metrics = KinesisWorkshopMetrics(SimpleMeterRegistry())
        val runner = KinesisDemoRunner(
            properties = properties,
            service = service,
            demoScope = scope,
            healthIndicator = KinesisWorkshopHealthIndicator(properties),
            metrics = metrics,
        )
        return Fixture(scope, operations, service, runner)
    }

    private data class Fixture(
        val scope: KinesisDemoScope,
        val operations: LocalKinesisOperations,
        val service: KinesisStreamService,
        val runner: KinesisDemoRunner,
    )
}
