package io.bluetape4k.workshop.aws.observability

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchLogsOperations
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchMeterPublishingOperations
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchOperations
import io.bluetape4k.aws.spring.imds.ImdsOperations
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse
import kotlin.coroutines.cancellation.CancellationException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderTelemetryServiceTest {

    @Test
    fun `publishes metric log event meter snapshot and skips metadata by default`() = runSuspendIO {
        val fixture = serviceFixture()

        val report = fixture.service.recordOrder(
            OrderTelemetryRequest(
                eventId = "order-100",
                outcome = TelemetryOutcome.SUCCESS,
                message = "accepted token=secret-value",
            )
        )

        report.metric.state shouldBeEqualTo PublishState.PUBLISHED
        report.logs.state shouldBeEqualTo PublishState.PUBLISHED
        report.meterSnapshot.state shouldBeEqualTo PublishState.PUBLISHED
        report.metadata.state shouldBeEqualTo PublishState.SKIPPED

        fixture.cloudWatch.namespaces.single() shouldBeEqualTo "Bluetape4k/Workshop"
        val metric = fixture.cloudWatch.metricData.single()
        metric.metricName() shouldBeEqualTo "OrderTelemetryOutcome"
        metric.value() shouldBeEqualTo 1.0
        metric.dimensions().associate { it.name() to it.value() } shouldBeEqualTo mapOf(
            "Outcome" to "success",
            "Service" to "order-service",
            "Source" to "workshop",
        )

        fixture.logs.logGroupNames.single() shouldBeEqualTo "/bluetape4k/workshop/orders"
        fixture.logs.logStreamNames.single() shouldBeEqualTo "local"
        val eventMessage = fixture.logs.events.single().message()
        eventMessage shouldContain "\"eventId\":\"order-100\""
        eventMessage shouldContain "\"outcome\":\"success\""
        eventMessage shouldNotContain "secret-value"
        eventMessage shouldNotContain "token="

        fixture.meterPublisher.publishedNames.single() shouldBeEqualTo "workshop.aws.order.telemetry.requests"
        fixture.meterRegistry.counter("workshop.aws.order.telemetry.requests", "outcome", "success").count() shouldBeEqualTo 1.0
        fixture.imds.requestedPaths.isNotEmpty().shouldBeFalse()
    }

    @Test
    fun `reports independent partial failures without hiding successful publishers`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.cloudWatch.failure = IllegalStateException("metric denied")
        fixture.meterPublisher.failure = IllegalStateException("meter denied")

        val report = fixture.service.recordOrder(
            OrderTelemetryRequest(
                eventId = "order-101",
                outcome = TelemetryOutcome.FAILURE,
                message = "payment failed",
            )
        )

        report.metric.state shouldBeEqualTo PublishState.FAILED
        report.logs.state shouldBeEqualTo PublishState.PUBLISHED
        report.meterSnapshot.state shouldBeEqualTo PublishState.FAILED
        report.metadata.state shouldBeEqualTo PublishState.SKIPPED
        report.metric.message shouldContain "metric denied"
        report.meterSnapshot.message shouldContain "meter denied"
        fixture.logs.events shouldHaveSize 1
    }

    @Test
    fun `rethrows cancellation instead of returning failed report`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.cloudWatch.failure = CancellationException("publish cancelled")

        assertFailsWith<CancellationException> {
            fixture.service.recordOrder(
                OrderTelemetryRequest(
                    eventId = "order-102",
                    outcome = TelemetryOutcome.SUCCESS,
                    message = "cancel me",
                )
            )
        }
    }

    @Test
    fun `explicit metadata opt in reads safe helper values only`() = runSuspendIO {
        val fixture = serviceFixture()

        val report = fixture.service.recordOrder(
            OrderTelemetryRequest(
                eventId = "order-103",
                outcome = TelemetryOutcome.SUCCESS,
                includeMetadata = true,
            )
        )

        report.metadata.state shouldBeEqualTo PublishState.PUBLISHED
        report.metadata.instanceId shouldBeEqualTo "i-1234567890abcdef0"
        report.metadata.region shouldBeEqualTo "ap-northeast-2"
        report.metadata.availabilityZone shouldBeEqualTo "ap-northeast-2a"
        fixture.imds.requestedPaths.any { it.contains("security-credentials") }.shouldBeFalse()
    }

    private fun serviceFixture(): ServiceFixture {
        val properties = AwsObservabilityProperties(
            namespace = "Bluetape4k/Workshop",
            logGroupName = "/bluetape4k/workshop/orders",
            logStreamName = "local",
            serviceName = "order-service",
            sourceName = "workshop",
            maxFieldLength = 80,
        )
        val cloudWatch = CapturingCloudWatchOperations()
        val logs = CapturingCloudWatchLogsOperations()
        val meterPublisher = CapturingMeterPublisher()
        val imds = CapturingImdsOperations()
        val meterRegistry = SimpleMeterRegistry()

        return ServiceFixture(
            service = OrderTelemetryService(
                properties = properties,
                cloudWatchOperations = cloudWatch,
                cloudWatchLogsOperations = logs,
                meterPublishingOperations = meterPublisher,
                imdsOperations = imds,
                meterRegistry = meterRegistry,
            ),
            cloudWatch = cloudWatch,
            logs = logs,
            meterPublisher = meterPublisher,
            imds = imds,
            meterRegistry = meterRegistry,
        )
    }

    private class ServiceFixture(
        val service: OrderTelemetryService,
        val cloudWatch: CapturingCloudWatchOperations,
        val logs: CapturingCloudWatchLogsOperations,
        val meterPublisher: CapturingMeterPublisher,
        val imds: CapturingImdsOperations,
        val meterRegistry: SimpleMeterRegistry,
    )

    private class CapturingCloudWatchOperations : CloudWatchOperations {
        val namespaces = mutableListOf<String>()
        val metricData = mutableListOf<MetricDatum>()
        var failure: Throwable? = null

        override suspend fun putMetricData(metricData: List<MetricDatum>): List<PutMetricDataResponse> {
            failure?.let { throw it }
            this.metricData += metricData
            return metricData.map { PutMetricDataResponse.builder().build() }
        }

        override suspend fun putMetricData(
            namespace: String,
            metricData: List<MetricDatum>,
        ): List<PutMetricDataResponse> {
            failure?.let { throw it }
            namespaces += namespace
            this.metricData += metricData
            return metricData.map { PutMetricDataResponse.builder().build() }
        }

        override suspend fun putMetricDatum(metricDatum: MetricDatum): PutMetricDataResponse {
            failure?.let { throw it }
            metricData += metricDatum
            return PutMetricDataResponse.builder().build()
        }

        override suspend fun putMetricDatum(
            namespace: String,
            metricDatum: MetricDatum,
        ): PutMetricDataResponse {
            failure?.let { throw it }
            namespaces += namespace
            metricData += metricDatum
            return PutMetricDataResponse.builder().build()
        }

        override suspend fun listMetrics(
            namespace: String?,
            metricName: String?,
            dimensions: List<DimensionFilter>?,
        ): ListMetricsResponse = ListMetricsResponse.builder().build()
    }

    private class CapturingCloudWatchLogsOperations : CloudWatchLogsOperations {
        val logGroupNames = mutableListOf<String>()
        val logStreamNames = mutableListOf<String>()
        val events = mutableListOf<InputLogEvent>()
        var failure: Throwable? = null

        override suspend fun createLogGroup(logGroupName: String): CreateLogGroupResponse =
            CreateLogGroupResponse.builder().build()

        override suspend fun createLogStream(
            logGroupName: String,
            logStreamName: String,
        ): CreateLogStreamResponse = CreateLogStreamResponse.builder().build()

        override suspend fun putLogEvents(logEvents: List<InputLogEvent>): List<PutLogEventsResponse> {
            failure?.let { throw it }
            events += logEvents
            return logEvents.map { PutLogEventsResponse.builder().build() }
        }

        override suspend fun putLogEvents(
            logGroupName: String,
            logStreamName: String,
            logEvents: List<InputLogEvent>,
        ): List<PutLogEventsResponse> {
            failure?.let { throw it }
            logGroupNames += logGroupName
            logStreamNames += logStreamName
            events += logEvents
            return logEvents.map { PutLogEventsResponse.builder().build() }
        }

        override suspend fun describeLogGroups(logGroupNamePrefix: String?): DescribeLogGroupsResponse =
            DescribeLogGroupsResponse.builder().build()

        override suspend fun describeLogStreams(
            logGroupName: String,
            logStreamNamePrefix: String?,
        ): DescribeLogStreamsResponse = DescribeLogStreamsResponse.builder().build()
    }

    private class CapturingMeterPublisher : CloudWatchMeterPublishingOperations {
        val publishedNames = mutableListOf<String>()
        var failure: Throwable? = null

        override suspend fun publishMeters(predicate: (io.micrometer.core.instrument.Meter) -> Boolean): List<PutMetricDataResponse> {
            failure?.let { throw it }
            return listOf(PutMetricDataResponse.builder().build())
        }

        override suspend fun publishMeter(name: String): List<PutMetricDataResponse> {
            failure?.let { throw it }
            publishedNames += name
            return listOf(PutMetricDataResponse.builder().build())
        }
    }

    private class CapturingImdsOperations : ImdsOperations {
        val requestedPaths = mutableListOf<String>()

        override suspend fun get(path: String): String {
            requestedPaths += path
            return when (path) {
                "/latest/meta-data/instance-id" -> "i-1234567890abcdef0"
                "/latest/meta-data/placement/region" -> "ap-northeast-2"
                "/latest/meta-data/placement/availability-zone" -> "ap-northeast-2a"
                else -> error("Unexpected metadata path: $path")
            }
        }

        override suspend fun getList(path: String): List<String> {
            requestedPaths += path
            return emptyList()
        }
    }
}
