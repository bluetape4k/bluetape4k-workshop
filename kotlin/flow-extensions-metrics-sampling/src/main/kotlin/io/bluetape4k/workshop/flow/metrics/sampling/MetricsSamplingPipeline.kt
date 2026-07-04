package io.bluetape4k.workshop.flow.metrics.sampling

import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.coroutines.flow.extensions.mapResultCatching
import io.bluetape4k.coroutines.flow.extensions.pairwise
import io.bluetape4k.coroutines.flow.extensions.takeUntil
import io.bluetape4k.coroutines.flow.extensions.throttleLeading
import io.bluetape4k.coroutines.flow.extensions.throttleTrailing
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Teachable Flow pipeline for noisy metrics and sensor streams.
 *
 * ## Contract
 * - Leading samples favor fast feedback for alert previews.
 * - Trailing samples favor stable dashboards after each throttle window.
 * - Adjacent deltas and trends are derived with Flow extensions instead of
 *   manual mutable state.
 * - Lifecycle stop signals are delegated to `takeUntil`; materialized Result
 *   streams use `mapResultCatching` so cooperative cancellation is preserved.
 *
 * ```kotlin
 * val pipeline = MetricsSamplingPipeline()
 * val dashboard = pipeline.dashboardSamples(samples, 500.milliseconds)
 * ```
 */
class MetricsSamplingPipeline {

    /**
     * Emits the first sample observed in each throttle window.
     */
    fun leadingPreview(samples: Flow<MetricSample>, window: Duration): Flow<MetricSample> =
        samples
            .throttleLeading(window)
            .log("metrics-leading-preview")

    /**
     * Emits the last sample observed in each throttle window.
     */
    fun dashboardSamples(samples: Flow<MetricSample>, window: Duration): Flow<MetricSample> =
        samples
            .throttleTrailing(window)
            .log("metrics-dashboard")

    /**
     * Converts adjacent samples from one metric stream into deltas.
     */
    fun deltas(samples: Flow<MetricSample>): Flow<MetricDelta> =
        samples.pairwise(MetricDelta::from)

    /**
     * Emits only adjacent trends that cross the configured absolute threshold.
     */
    fun significantChanges(
        samples: Flow<MetricSample>,
        absoluteThreshold: Double,
    ): Flow<MetricTrend> {
        absoluteThreshold.requirePositiveFiniteNumber("absoluteThreshold")

        return deltas(samples)
            .map { MetricTrend.from(it, absoluteThreshold) }
            .filter { it.significant }
            .log("metrics-significant-changes")
    }

    /**
     * Converts materialized delta results into trend results without wrapping
     * coroutine cancellation as a domain failure.
     */
    fun significantChangeResults(
        deltas: Flow<Result<MetricDelta>>,
        absoluteThreshold: Double,
    ): Flow<Result<MetricTrend>> {
        absoluteThreshold.requirePositiveFiniteNumber("absoluteThreshold")

        return deltas
            .mapResultCatching { MetricTrend.from(it, absoluteThreshold) }
            .filter { result -> result.getOrNull()?.significant ?: true }
            .log("metrics-significant-change-results")
    }

    /**
     * Emits samples until a lifecycle stop signal arrives.
     */
    fun lifecycleBoundSamples(
        samples: Flow<MetricSample>,
        stopSignal: Flow<Any?>,
    ): Flow<MetricSample> =
        samples
            .takeUntil(stopSignal)
            .log("metrics-lifecycle")
}
