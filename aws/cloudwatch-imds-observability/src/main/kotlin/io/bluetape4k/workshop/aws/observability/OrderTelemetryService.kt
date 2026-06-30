package io.bluetape4k.workshop.aws.observability

import io.bluetape4k.aws.spring.cloudwatch.CloudWatchLogsOperations
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchMeterPublishingOperations
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchOperations
import io.bluetape4k.aws.spring.imds.ImdsOperations
import io.bluetape4k.support.requireNotBlank
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.cloudwatch.model.Dimension
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

@Service
class OrderTelemetryService(
    private val properties: AwsObservabilityProperties,
    private val cloudWatchOperations: CloudWatchOperations,
    private val cloudWatchLogsOperations: CloudWatchLogsOperations,
    private val meterPublishingOperations: CloudWatchMeterPublishingOperations,
    private val imdsOperations: ImdsOperations,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {

    suspend fun recordOrder(request: OrderTelemetryRequest): OrderTelemetryReport {
        validate(request)

        val timer = Timer.start(meterRegistry)
        try {
            recordRequestCounter(request.outcome)

            val metric = publishMetric(request)
            val logs = publishLogEvent(request)
            val meterSnapshot = publishMeterSnapshot()
            val metadata = if (request.includeMetadata || properties.metadata.enabled) {
                readMetadata()
            } else {
                MetadataSnapshot.skipped()
            }

            return OrderTelemetryReport(
                outcome = request.outcome,
                metric = metric,
                logs = logs,
                meterSnapshot = meterSnapshot,
                metadata = metadata,
            )
        } finally {
            timer.stop(
                Timer.builder(ORDER_TELEMETRY_LATENCY)
                    .tag(OUTCOME_TAG, request.outcome.tagValue)
                    .register(meterRegistry)
            )
        }
    }

    suspend fun readMetadata(): MetadataSnapshot =
        try {
            MetadataSnapshot(
                state = PublishState.PUBLISHED,
                instanceId = imdsOperations.instanceId(),
                region = imdsOperations.region(),
                availabilityZone = imdsOperations.availabilityZone(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MetadataSnapshot.failed(e)
        }

    private fun recordRequestCounter(outcome: TelemetryOutcome) {
        Counter.builder(ORDER_TELEMETRY_REQUESTS)
            .tag(OUTCOME_TAG, outcome.tagValue)
            .register(meterRegistry)
            .increment()
    }

    private fun validate(request: OrderTelemetryRequest) {
        try {
            request.eventId.requireNotBlank("eventId")
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("eventId must not be blank.", e)
        }
    }

    private suspend fun publishMetric(request: OrderTelemetryRequest): PublishStatus =
        try {
            cloudWatchOperations.putMetricDatum(
                properties.namespace,
                MetricDatum.builder()
                    .metricName("OrderTelemetryOutcome")
                    .dimensions(
                        Dimension.builder().name("Outcome").value(request.outcome.tagValue).build(),
                        Dimension.builder().name("Service").value(properties.serviceName).build(),
                        Dimension.builder().name("Source").value(properties.sourceName).build(),
                    )
                    .unit(StandardUnit.COUNT)
                    .value(1.0)
                    .build()
            )
            PublishStatus.published()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailureCounter("metric")
            PublishStatus.failed(e)
        }

    private suspend fun publishLogEvent(request: OrderTelemetryRequest): PublishStatus =
        try {
            cloudWatchLogsOperations.putLogEvents(
                properties.logGroupName,
                properties.logStreamName,
                listOf(
                    InputLogEvent.builder()
                        .timestamp(clock.millis())
                        .message(toJsonEvent(request))
                        .build()
                )
            )
            PublishStatus.published()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailureCounter("logs")
            PublishStatus.failed(e)
        }

    private suspend fun publishMeterSnapshot(): PublishStatus =
        try {
            meterPublishingOperations.publishMeter(ORDER_TELEMETRY_REQUESTS)
            PublishStatus.published()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailureCounter("meter")
            PublishStatus.failed(e)
        }

    private fun recordFailureCounter(publisher: String) {
        Counter.builder(ORDER_TELEMETRY_FAILURES)
            .tag("publisher", publisher)
            .register(meterRegistry)
            .increment()
    }

    private fun toJsonEvent(request: OrderTelemetryRequest): String {
        val fields = linkedMapOf(
            "eventId" to request.eventId.take(properties.maxFieldLength),
            "outcome" to request.outcome.tagValue,
            "service" to properties.serviceName,
            "source" to properties.sourceName,
            "message" to sanitize(request.message),
        )
        return fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escapeJson(key)}\":\"${escapeJson(value)}\""
        }
    }

    private fun sanitize(value: String): String =
        SENSITIVE_PATTERNS.fold(value) { acc, pattern ->
            pattern.replace(acc, "[redacted]")
        }.take(properties.maxFieldLength)

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    companion object {
        const val ORDER_TELEMETRY_REQUESTS = "workshop.aws.order.telemetry.requests"
        const val ORDER_TELEMETRY_LATENCY = "workshop.aws.order.telemetry.latency"
        const val ORDER_TELEMETRY_FAILURES = "workshop.aws.order.telemetry.failures"
        private const val OUTCOME_TAG = "outcome"

        private val SENSITIVE_PATTERNS = listOf(
            Regex("\\b(?i)(token|secret|password|credential)\\s*[:=]\\s*[^\\s,;]+"),
            Regex("\\b(?i)(authorization)\\s*[:=]\\s*[^\\s,;]+"),
            Regex("\\b(?i)bearer\\s+[^\\s,;]+"),
        )
    }
}
