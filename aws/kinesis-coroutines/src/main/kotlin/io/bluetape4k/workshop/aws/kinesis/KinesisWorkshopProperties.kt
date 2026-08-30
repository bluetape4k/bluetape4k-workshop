package io.bluetape4k.workshop.aws.kinesis

import java.io.Serializable
import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Kinesis 코루틴 워크숍의 실행 경계를 정의합니다.
 *
 * 기본값은 AWS credential이나 외부 네트워크가 필요 없는 `local` 프로필입니다. 실제 AWS
 * 호출은 `real-aws` 프로필을 명시적으로 선택했을 때만 허용합니다.
 */
@ConfigurationProperties(prefix = "kinesis.workshop")
data class KinesisWorkshopProperties(
    val profile: String = LOCAL_PROFILE,
    val streamName: String = "kinesis-coroutines-workshop",
    val partitionKey: String = "workshop-partition",
    val shardId: String = DEFAULT_SHARD_ID,
    val consumerGroup: String = DEFAULT_CONSUMER_GROUP,
    val streamIdentity: String = DEFAULT_STREAM_IDENTITY,
    val ownerId: String = DEFAULT_OWNER_ID,
    val maxShardConcurrency: Int = DEFAULT_MAX_SHARD_CONCURRENCY,
    val runDemo: Boolean = true,
    val batchLimit: Int = 100,
    val pollInterval: Duration = Duration.ofMillis(200),
    val emptyBackoff: Duration = Duration.ofSeconds(1),
    val maxAggregatePayloadBytes: Long = MAX_AGGREGATE_PAYLOAD_BYTES,
    val maxIteratorRetries: Int = 3,
    val maxThrottleRetries: Int = 5,
    val readinessTimeout: Duration = Duration.ofSeconds(30),
    val readinessPollInterval: Duration = Duration.ofMillis(250),
    val endpoint: URI? = null,
) : Serializable {

    init {
        require(profile == LOCAL_PROFILE || profile == REAL_AWS_PROFILE) {
            "profile must be local or real-aws."
        }
        require(streamName.isNotBlank()) { "streamName must not be blank." }
        require(partitionKey.isNotBlank()) { "partitionKey must not be blank." }
        require(shardId.isNotBlank()) { "shardId must not be blank." }
        require(consumerGroup.isNotBlank()) { "consumerGroup must not be blank." }
        require(streamIdentity.isNotBlank()) { "streamIdentity must not be blank." }
        require(ownerId.isNotBlank()) { "ownerId must not be blank." }
        require(maxShardConcurrency >= 1) { "maxShardConcurrency must be greater than zero." }
        require(batchLimit in 1..MAX_BATCH_LIMIT) {
            "batchLimit must be between 1 and $MAX_BATCH_LIMIT."
        }
        require(pollInterval >= MIN_POLL_INTERVAL) {
            "pollInterval must be greater than or equal to 200ms."
        }
        require(emptyBackoff > Duration.ZERO) { "emptyBackoff must be greater than zero." }
        require(maxAggregatePayloadBytes in 1..MAX_AGGREGATE_PAYLOAD_BYTES) {
            "maxAggregatePayloadBytes must not exceed 1MiB."
        }
        require(maxIteratorRetries >= 1) { "maxIteratorRetries must be greater than or equal to 1." }
        require(maxThrottleRetries >= 1) { "maxThrottleRetries must be greater than or equal to 1." }
        require(readinessTimeout > Duration.ZERO) { "readinessTimeout must be greater than zero." }
        require(readinessPollInterval > Duration.ZERO) {
            "readinessPollInterval must be greater than zero."
        }
        endpoint?.let(::validateEndpoint)
    }

    private fun validateEndpoint(uri: URI) {
        require(uri.userInfo == null && (uri.scheme == "http" || uri.scheme == "https")) {
            "endpoint must use HTTP(S) without user-info."
        }

        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
        require(host in ALLOWED_ENDPOINT_HOSTS) {
            "endpoint host is not allowed."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        const val LOCAL_PROFILE: String = "local"
        const val REAL_AWS_PROFILE: String = "real-aws"
        const val DEFAULT_SHARD_ID: String = "shardId-000000000000"
        const val DEFAULT_CONSUMER_GROUP: String = "kinesis-coroutines-consumer"
        const val DEFAULT_STREAM_IDENTITY: String = "kinesis-coroutines-workshop-v1"
        const val DEFAULT_OWNER_ID: String = "kinesis-coroutines-worker-1"
        const val DEFAULT_MAX_SHARD_CONCURRENCY: Int = 2
        const val MAX_BATCH_LIMIT: Int = 1_000
        const val MAX_AGGREGATE_PAYLOAD_BYTES: Long = 1L * 1024 * 1024
        val MIN_POLL_INTERVAL: Duration = Duration.ofMillis(200)

        private val ALLOWED_ENDPOINT_HOSTS = setOf(
            "localhost",
            "127.0.0.1",
            "::1",
            "localstack",
            "kinesis",
        )
    }
}
