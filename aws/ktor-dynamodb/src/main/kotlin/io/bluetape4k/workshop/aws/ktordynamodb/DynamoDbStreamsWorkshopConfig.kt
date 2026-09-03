package io.bluetape4k.workshop.aws.ktordynamodb

import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsRecordFlowOptions
import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsStartingPosition
import kotlin.time.Duration.Companion.milliseconds

/** Ktor DynamoDB Streams 실습의 명시적 opt-in과 bounded Flow 설정입니다. */
internal data class DynamoDbStreamsWorkshopConfig(
    val enabled: Boolean,
    val startingPosition: DynamoDbStreamsStartingPosition,
    val maxRecords: Int,
    val flowOptions: DynamoDbStreamsRecordFlowOptions,
) {
    companion object {
        private const val ENABLED_PROPERTY = "bluetape4k.aws.dynamodb.streams.enabled"
        private const val POSITION_PROPERTY = "bluetape4k.aws.dynamodb.streams.starting-position"
        private const val MAX_RECORDS_PROPERTY = "bluetape4k.aws.dynamodb.streams.max-records"
        private const val BATCH_LIMIT_PROPERTY = "bluetape4k.aws.dynamodb.streams.batch-limit"
        private const val POLL_INTERVAL_PROPERTY = "bluetape4k.aws.dynamodb.streams.poll-interval-millis"
        private const val EMPTY_BACKOFF_PROPERTY = "bluetape4k.aws.dynamodb.streams.empty-backoff-millis"

        private const val DEFAULT_MAX_RECORDS: Int = 10
        private const val DEFAULT_BATCH_LIMIT: Int = 100
        private const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 200L
        private const val DEFAULT_EMPTY_BACKOFF_MILLIS: Long = 1_000L

        fun fromSystemProperties(): DynamoDbStreamsWorkshopConfig =
            fromProperties(System::getProperty)

        fun fromProperties(property: (String) -> String?): DynamoDbStreamsWorkshopConfig {
            val enabled = property(ENABLED_PROPERTY)?.trim()?.lowercase()?.let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> throw invalid("$ENABLED_PROPERTY must be true or false.")
                }
            } ?: false

            val startingPosition = when (property(POSITION_PROPERTY)?.trim()?.lowercase() ?: "trim_horizon") {
                "trim_horizon", "trim-horizon" -> DynamoDbStreamsStartingPosition.TrimHorizon
                "latest" -> DynamoDbStreamsStartingPosition.Latest
                else -> throw invalid("$POSITION_PROPERTY must be trim_horizon or latest.")
            }
            val maxRecords = positiveInt(property(MAX_RECORDS_PROPERTY), DEFAULT_MAX_RECORDS, MAX_RECORDS_PROPERTY)
            val batchLimit = positiveInt(property(BATCH_LIMIT_PROPERTY), DEFAULT_BATCH_LIMIT, BATCH_LIMIT_PROPERTY)
            val pollIntervalMillis = positiveLong(
                property(POLL_INTERVAL_PROPERTY),
                DEFAULT_POLL_INTERVAL_MILLIS,
                POLL_INTERVAL_PROPERTY,
            )
            val emptyBackoffMillis = positiveLong(
                property(EMPTY_BACKOFF_PROPERTY),
                DEFAULT_EMPTY_BACKOFF_MILLIS,
                EMPTY_BACKOFF_PROPERTY,
            )

            if (maxRecords > DynamoDbStreamsRecordFlowOptions.MAX_BATCH_LIMIT) {
                throw invalid("$MAX_RECORDS_PROPERTY must be <= ${DynamoDbStreamsRecordFlowOptions.MAX_BATCH_LIMIT}.")
            }

            val flowOptions = try {
                DynamoDbStreamsRecordFlowOptions(
                    batchLimit = batchLimit,
                    pollInterval = pollIntervalMillis.milliseconds,
                    emptyBackoff = emptyBackoffMillis.milliseconds,
                    maxShardConcurrency = 1,
                )
            } catch (e: IllegalArgumentException) {
                throw invalid(e.message ?: "Invalid DynamoDB Streams Flow options.")
            }

            return DynamoDbStreamsWorkshopConfig(
                enabled = enabled,
                startingPosition = startingPosition,
                maxRecords = maxRecords,
                flowOptions = flowOptions,
            )
        }

        private fun positiveInt(value: String?, defaultValue: Int, propertyName: String): Int =
            value?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }
                ?: if (value == null || value.trim().isEmpty()) defaultValue
                else throw invalid("$propertyName must be a positive integer.")

        private fun positiveLong(value: String?, defaultValue: Long, propertyName: String): Long =
            value?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()?.takeIf { it > 0 }
                ?: if (value == null || value.trim().isEmpty()) defaultValue
                else throw invalid("$propertyName must be a positive integer.")

        private fun invalid(message: String): OrderSessionValidationException =
            OrderSessionValidationException(message)
    }
}
