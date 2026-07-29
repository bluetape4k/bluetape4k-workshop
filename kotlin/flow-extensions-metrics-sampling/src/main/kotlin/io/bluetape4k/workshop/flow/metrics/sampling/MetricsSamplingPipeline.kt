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
 * noisy metric 과 sensor stream 을 다루는 학습용 Flow pipeline 입니다.
 *
 * ## Contract
 * - leading sample 은 alert preview 의 빠른 feedback 을 우선합니다.
 * - trailing sample 은 각 throttle window 이후 안정적인 dashboard 값을 우선합니다.
 * - adjacent delta 와 trend 는 manual mutable state 대신 Flow extension 으로 파생합니다.
 * - lifecycle stop signal 은 `takeUntil` 에 위임하고, materialized Result stream 은 cooperative cancellation 이 유지되도록 `mapResultCatching` 을 사용합니다.
 *
 * ```kotlin
 * val pipeline = MetricsSamplingPipeline()
 * val dashboard = pipeline.dashboardSamples(samples, 500.milliseconds)
 * ```
 */
class MetricsSamplingPipeline {

    /**
     * 각 throttle window 에서 관측된 첫 sample 을 방출합니다.
     */
    fun leadingPreview(samples: Flow<MetricSample>, window: Duration): Flow<MetricSample> =
        samples
            .throttleLeading(window)
            .log("metrics-leading-preview")

    /**
     * 각 throttle window 에서 관측된 마지막 sample 을 방출합니다.
     */
    fun dashboardSamples(samples: Flow<MetricSample>, window: Duration): Flow<MetricSample> =
        samples
            .throttleTrailing(window)
            .log("metrics-dashboard")

    /**
     * 하나의 metric stream 에서 인접한 sample 을 delta 로 변환합니다.
     */
    fun deltas(samples: Flow<MetricSample>): Flow<MetricDelta> =
        samples.pairwise(MetricDelta::from)

    /**
     * 설정된 absolute threshold 를 넘는 adjacent trend 만 방출합니다.
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
     * coroutine cancellation 을 domain failure 로 감싸지 않고 materialized delta result 를 trend result 로 변환합니다.
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
     * lifecycle stop signal 이 도착할 때까지 sample 을 방출합니다.
     */
    fun lifecycleBoundSamples(
        samples: Flow<MetricSample>,
        stopSignal: Flow<Any?>,
    ): Flow<MetricSample> =
        samples
            .takeUntil(stopSignal)
            .log("metrics-lifecycle")
}
