package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.aws.spring.kinesis.KinesisOperations
import io.bluetape4k.aws.kinesis.InMemoryKinesisCheckpointStore
import io.bluetape4k.aws.kinesis.InMemoryKinesisLeaseStore
import io.bluetape4k.aws.kinesis.KinesisCheckpointStore
import io.bluetape4k.aws.kinesis.KinesisLeaseStore
import io.bluetape4k.jackson3.Jackson
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import tools.jackson.databind.json.JsonMapper

/** 공통 service와 passive scope를 profile-independent하게 wiring합니다. */
@Configuration(proxyBeanMethods = false)
class KinesisWorkshopConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun kinesisDemoScope(): KinesisDemoScope = KinesisDemoScope()

    @Bean
    @ConditionalOnMissingBean
    fun kinesisJsonMapper(): JsonMapper = Jackson.defaultJsonMapper

    @Bean
    @ConditionalOnMissingBean(KinesisCheckpointStore::class)
    fun kinesisCheckpointStore(): KinesisCheckpointStore = InMemoryKinesisCheckpointStore()

    @Bean
    @ConditionalOnMissingBean(KinesisLeaseStore::class)
    fun kinesisLeaseStore(): KinesisLeaseStore = InMemoryKinesisLeaseStore()

    @Bean
    fun kinesisStreamService(
        properties: KinesisWorkshopProperties,
        operations: KinesisOperations,
        objectMapper: JsonMapper,
        demoScope: KinesisDemoScope,
        consumerClient: KinesisAsyncClient,
        checkpointStore: KinesisCheckpointStore,
        leaseStore: KinesisLeaseStore,
    ): KinesisStreamService = KinesisStreamService(
        properties = properties,
        operations = operations,
        objectMapper = objectMapper,
        demoScope = demoScope,
        consumerClient = consumerClient,
        checkpointStore = checkpointStore,
        leaseStore = leaseStore,
    )
}
