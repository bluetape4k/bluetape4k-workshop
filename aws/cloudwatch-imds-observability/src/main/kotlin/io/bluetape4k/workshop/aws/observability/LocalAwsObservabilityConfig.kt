package io.bluetape4k.workshop.aws.observability

import io.bluetape4k.aws.spring.cloudwatch.CloudWatchLogsOperations
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchMeterPublishingOperations
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchMeterPublishingTemplate
import io.bluetape4k.aws.spring.cloudwatch.CloudWatchOperations
import io.bluetape4k.aws.spring.imds.ImdsOperations
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
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

@Configuration(proxyBeanMethods = false)
@Profile("!real-aws")
class LocalAwsObservabilityConfig {

    @Bean
    @ConditionalOnMissingBean
    fun localCloudWatchOperations(): CloudWatchOperations = LocalCloudWatchOperations()

    @Bean
    @ConditionalOnMissingBean
    fun localCloudWatchLogsOperations(): CloudWatchLogsOperations = LocalCloudWatchLogsOperations()

    @Bean
    @ConditionalOnMissingBean
    fun localImdsOperations(): ImdsOperations = LocalImdsOperations()

    @Bean
    @ConditionalOnMissingBean
    fun cloudWatchMeterPublishingOperations(
        meterRegistry: MeterRegistry,
        cloudWatchOperations: CloudWatchOperations,
    ): CloudWatchMeterPublishingOperations =
        CloudWatchMeterPublishingTemplate(meterRegistry, cloudWatchOperations)
}

private class LocalCloudWatchOperations : CloudWatchOperations {

    override suspend fun putMetricData(metricData: List<MetricDatum>): List<PutMetricDataResponse> =
        metricData.map { PutMetricDataResponse.builder().build() }

    override suspend fun putMetricData(
        namespace: String,
        metricData: List<MetricDatum>,
    ): List<PutMetricDataResponse> =
        metricData.map { PutMetricDataResponse.builder().build() }

    override suspend fun putMetricDatum(metricDatum: MetricDatum): PutMetricDataResponse =
        PutMetricDataResponse.builder().build()

    override suspend fun putMetricDatum(
        namespace: String,
        metricDatum: MetricDatum,
    ): PutMetricDataResponse =
        PutMetricDataResponse.builder().build()

    override suspend fun listMetrics(
        namespace: String?,
        metricName: String?,
        dimensions: List<DimensionFilter>?,
    ): ListMetricsResponse = ListMetricsResponse.builder().build()
}

private class LocalCloudWatchLogsOperations : CloudWatchLogsOperations {

    override suspend fun createLogGroup(logGroupName: String): CreateLogGroupResponse =
        CreateLogGroupResponse.builder().build()

    override suspend fun createLogStream(
        logGroupName: String,
        logStreamName: String,
    ): CreateLogStreamResponse = CreateLogStreamResponse.builder().build()

    override suspend fun putLogEvents(logEvents: List<InputLogEvent>): List<PutLogEventsResponse> =
        logEvents.map { PutLogEventsResponse.builder().build() }

    override suspend fun putLogEvents(
        logGroupName: String,
        logStreamName: String,
        logEvents: List<InputLogEvent>,
    ): List<PutLogEventsResponse> =
        logEvents.map { PutLogEventsResponse.builder().build() }

    override suspend fun describeLogGroups(logGroupNamePrefix: String?): DescribeLogGroupsResponse =
        DescribeLogGroupsResponse.builder().build()

    override suspend fun describeLogStreams(
        logGroupName: String,
        logStreamNamePrefix: String?,
    ): DescribeLogStreamsResponse = DescribeLogStreamsResponse.builder().build()
}

private class LocalImdsOperations : ImdsOperations {

    override suspend fun get(path: String): String =
        when (path) {
            "/latest/meta-data/instance-id" -> "local-instance"
            "/latest/meta-data/placement/region" -> "local-region"
            "/latest/meta-data/placement/availability-zone" -> "local-zone"
            else -> error("Unexpected local IMDS path: $path")
        }

    override suspend fun getList(path: String): List<String> =
        error("Unexpected local IMDS list path: $path")
}
