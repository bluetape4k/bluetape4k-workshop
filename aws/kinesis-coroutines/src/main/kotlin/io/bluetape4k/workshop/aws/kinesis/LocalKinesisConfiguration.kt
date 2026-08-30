package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.aws.spring.kinesis.KinesisOperations
import io.bluetape4k.aws.spring.kinesis.KinesisAutoConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

/** `local` profile에서만 deterministic fake를 등록합니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "kinesis.workshop",
    name = ["profile"],
    havingValue = KinesisWorkshopProperties.LOCAL_PROFILE,
    matchIfMissing = true,
)
@ImportAutoConfiguration(exclude = [KinesisAutoConfiguration::class])
class LocalKinesisConfiguration {

    @Bean
    @ConditionalOnMissingBean(KinesisOperations::class)
    fun localKinesisOperations(properties: KinesisWorkshopProperties): KinesisOperations =
        LocalKinesisOperations(properties.streamName, properties.shardId)

    @Bean
    @ConditionalOnMissingBean(KinesisAsyncClient::class)
    fun localKinesisConsumerClient(properties: KinesisWorkshopProperties): KinesisAsyncClient =
        LocalKinesisConsumerClient(
            configuredStreamName = properties.streamName,
            configuredShardId = properties.shardId,
        )
}
