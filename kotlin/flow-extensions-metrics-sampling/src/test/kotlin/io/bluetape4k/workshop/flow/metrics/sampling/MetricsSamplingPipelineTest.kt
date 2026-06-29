package io.bluetape4k.workshop.flow.metrics.sampling

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendTest
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test

class MetricsSamplingPipelineTest {

    private val pipeline = MetricsSamplingPipeline()

    @Test
    fun `leading preview emits first sample from each throttle window`() = runSuspendTest {
        val previews = pipeline.leadingPreview(highFrequencyCpuSamples(), 501.milliseconds).toList()

        previews.map { it.value } shouldBeEqualTo listOf(10.0, 40.0, 70.0, 100.0)
    }

    @Test
    fun `trailing dashboard emits last sample from each throttle window`() = runSuspendTest {
        val dashboard = pipeline.dashboardSamples(highFrequencyCpuSamples(), 501.milliseconds).toList()

        dashboard.map { it.value } shouldBeEqualTo listOf(30.0, 60.0, 90.0, 100.0)
    }

    @Test
    fun `adjacent deltas preserve order and value changes`() = runSuspendTest {
        val deltas = pipeline.deltas(
            flowOf(
                sample("queue.depth", 3.0, 1, "items"),
                sample("queue.depth", 5.0, 2, "items"),
                sample("queue.depth", 4.0, 3, "items"),
            ),
        ).toList()

        deltas.map { it.delta } shouldBeEqualTo listOf(2.0, -1.0)
        deltas.map { it.previous.value to it.current.value } shouldBeEqualTo listOf(3.0 to 5.0, 5.0 to 4.0)
        deltas.map { it.percentChange } shouldBeEqualTo listOf(66.66666666666666, -20.0)
    }

    @Test
    fun `significant changes keep threshold crossing trends`() = runSuspendTest {
        val trends = pipeline.significantChanges(
            flowOf(
                sample("latency.p95", 100.0, 1, "ms"),
                sample("latency.p95", 103.0, 2, "ms"),
                sample("latency.p95", 121.0, 3, "ms"),
                sample("latency.p95", 110.0, 4, "ms"),
            ),
            absoluteThreshold = 10.0,
        ).toList()

        trends.map { it.direction } shouldBeEqualTo listOf(MetricDirection.UP, MetricDirection.DOWN)
        trends.map { it.delta.delta } shouldBeEqualTo listOf(18.0, -11.0)
        trends.all { it.significant }.shouldBeTrue()

        val resultTrends = pipeline.significantChangeResults(
            flowOf(
                Result.success(
                    MetricDelta.from(
                        sample("latency.p95", 100.0, 1, "ms"),
                        sample("latency.p95", 121.0, 2, "ms"),
                    ),
                ),
            ),
            absoluteThreshold = 10.0,
        ).toList()

        resultTrends.map { it.getOrThrow().direction } shouldBeEqualTo listOf(MetricDirection.UP)
    }

    @Test
    fun `stop signal ends metric collection through takeUntil`() = runSuspendTest {
        val stop = MutableSharedFlow<Unit>()
        val secondSampleEmitted = CompletableDeferred<Unit>()
        val collection = async {
            pipeline.lifecycleBoundSamples(
                flow {
                    emit(sample("cpu.usage", 10.0, 1))
                    delay(100.milliseconds)
                    emit(sample("cpu.usage", 20.0, 2))
                    secondSampleEmitted.complete(Unit)
                    delay(300.milliseconds)
                    emit(sample("cpu.usage", 30.0, 3))
                },
                stop,
            ).toList()
        }

        secondSampleEmitted.await()
        stop.emit(Unit)

        collection.await().map { it.value } shouldBeEqualTo listOf(10.0, 20.0)
    }

    @Test
    fun `result trend mapping preserves cancellation instead of wrapping it`() = runSuspendTest {
        val cancellation = CancellationException("collector cancelled")

        val propagated = assertFailsWith<CancellationException> {
            pipeline.significantChangeResults(
                flowOf(Result.failure(cancellation)),
                absoluteThreshold = 10.0,
            ).toList()
        }

        propagated shouldBeEqualTo cancellation
    }

    @Test
    fun `domain values reject unsafe names values thresholds and copy bypasses`() = runSuspendTest {
        assertFailsWith<IllegalArgumentException> { MetricSample.of(" ", 1.0, t(1)) }
        assertFailsWith<IllegalArgumentException> { MetricSample.of("cpu\nusage", 1.0, t(1)) }
        assertFailsWith<IllegalArgumentException> { MetricSample.of("cpu.usage", Double.NaN, t(1)) }
        assertFailsWith<IllegalArgumentException> {
            pipeline.significantChanges(flowOf(sample("cpu.usage", 1.0, 1)), absoluteThreshold = 0.0).toList()
        }

        MetricSample::class.java.methods.any { it.name == "copy" }.shouldBeFalse()
    }

    private fun highFrequencyCpuSamples() = flow {
        for (index in 1..10) {
            emit(sample("cpu.usage", index * 10.0, index.toLong()))
            delay(200.milliseconds)
        }
    }

    private fun sample(
        name: String,
        value: Double,
        seconds: Long,
        unit: String = "percent",
    ): MetricSample =
        MetricSample.of(name = name, value = value, timestamp = t(seconds), unit = unit)

    private fun t(seconds: Long): Instant = Instant.EPOCH.plusSeconds(seconds)
}
