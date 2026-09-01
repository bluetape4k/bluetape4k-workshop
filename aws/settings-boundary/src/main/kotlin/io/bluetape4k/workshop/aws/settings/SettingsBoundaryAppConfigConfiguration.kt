package io.bluetape4k.workshop.aws.settings

import io.bluetape4k.aws.spring.AwsClientCustomizationContext
import io.bluetape4k.aws.spring.AwsSyncClientCustomizer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
import java.time.Duration

/** AppConfig Data runtime client에만 운영 timeout을 적용하는 예제 구성입니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.app-config",
    name = ["enabled"],
    havingValue = "true",
)
class SettingsBoundaryAppConfigConfiguration {

    @Bean
    fun appConfigRuntimeClientCustomizer(): AwsSyncClientCustomizer =
        appConfigTimeoutCustomizer(
            apiCallTimeout = Duration.ofSeconds(10),
            apiCallAttemptTimeout = Duration.ofSeconds(5),
        )
}

internal fun appConfigTimeoutCustomizer(
    apiCallTimeout: Duration,
    apiCallAttemptTimeout: Duration,
): AwsSyncClientCustomizer = AwsSyncClientCustomizer { context, builder ->
    if (context.serviceName == "appconfigdata") {
        val awsBuilder = builder as? AwsClientBuilder<*, *>
            ?: error("AppConfigData builder must implement AwsClientBuilder")
        awsBuilder.overrideConfiguration(
            awsBuilder.overrideConfiguration()
                .toBuilder()
                .apiCallTimeout(apiCallTimeout)
                .apiCallAttemptTimeout(apiCallAttemptTimeout)
                .build(),
        )
    }
}
