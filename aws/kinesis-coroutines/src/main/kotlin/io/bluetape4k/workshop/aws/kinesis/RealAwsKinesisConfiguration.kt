package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.aws.spring.kinesis.KinesisAutoConfiguration
import io.bluetape4k.aws.spring.kinesis.KinesisProperties
import io.bluetape4k.aws.spring.kinesis.KinesisOperations
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean

/** 명시적으로 `real-aws`를 선택했을 때만 upstream Kinesis 자동 구성을 활성화합니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "kinesis.workshop",
    name = ["profile"],
    havingValue = KinesisWorkshopProperties.REAL_AWS_PROFILE,
)
@ConditionalOnProperty(prefix = "bluetape4k.aws", name = ["enabled"], havingValue = "true")
@ImportAutoConfiguration(KinesisAutoConfiguration::class)
class RealAwsKinesisConfiguration {

    /** 검증된 workshop endpoint를 upstream client 설정으로 전달합니다. */
    @Bean
    fun workshopKinesisEndpointPostProcessor(properties: KinesisWorkshopProperties): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is KinesisProperties && properties.endpoint != null) {
                    bean.copy(endpointOverride = properties.endpoint)
                } else {
                    bean
                }
        }

    init {
        require(KinesisOperations::class.java.isAssignableFrom(Class.forName("io.bluetape4k.aws.spring.kinesis.KinesisCoroutinesTemplate"))) {
            "upstream KinesisCoroutinesTemplate is required for real-aws profile."
        }
    }
}
