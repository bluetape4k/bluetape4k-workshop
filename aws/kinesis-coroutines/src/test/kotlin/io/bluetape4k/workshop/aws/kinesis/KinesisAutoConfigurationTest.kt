package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.spring.kinesis.KinesisOperations
import io.bluetape4k.aws.spring.kinesis.KinesisProperties
import java.net.URI
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

class KinesisAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(KinesisCoroutinesApplication::class.java)
        .withPropertyValues(
            "spring.main.web-application-type=none",
            "kinesis.workshop.run-demo=false",
            "bluetape4k.aws.enabled=false",
            "bluetape4k.aws.kinesis.enabled=false",
        )

    @Test
    fun `default context is credential free and uses local operations`() {
        contextRunner.run { context ->
            context.getBeansOfType(KinesisOperations::class.java).size shouldBeEqualTo 1
            context.getBean(KinesisOperations::class.java)::class shouldBeEqualTo LocalKinesisOperations::class
            context.getBeansOfType(KinesisAsyncClient::class.java).size shouldBeEqualTo 1
            context.getBean(KinesisAsyncClient::class.java)::class shouldBeEqualTo LocalKinesisConsumerClient::class
            context.containsBean("kinesisDemoScope") shouldBeEqualTo true
            context.containsBean("kinesisStreamService") shouldBeEqualTo true
        }
    }

    @Test
    fun `local profile excludes upstream auto configuration even when aws flags are overridden`() {
        contextRunner
            .withPropertyValues(
                "kinesis.workshop.profile=local",
                "bluetape4k.aws.enabled=true",
                "bluetape4k.aws.kinesis.enabled=true",
                "bluetape4k.aws.region=us-east-1",
            )
            .run { context ->
                context.getBean(KinesisOperations::class.java)::class shouldBeEqualTo LocalKinesisOperations::class
                context.getBeansOfType(KinesisAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(KinesisAsyncClient::class.java)::class shouldBeEqualTo LocalKinesisConsumerClient::class
            }
    }

    @Test
    fun `real aws context selects upstream operations only when explicitly enabled`() {
        contextRunner
            .withPropertyValues(
                "kinesis.workshop.profile=real-aws",
                "bluetape4k.aws.enabled=true",
                "bluetape4k.aws.kinesis.enabled=true",
                "bluetape4k.aws.region=us-east-1",
                "bluetape4k.aws.kinesis.region=us-east-1",
                "kinesis.workshop.endpoint=http://localhost:4566",
            )
            .run { context ->
                context.getBean(KinesisOperations::class.java)::class.qualifiedName shouldBeEqualTo
                    "io.bluetape4k.aws.spring.kinesis.KinesisCoroutinesTemplate"
                context.getBeansOfType(LocalKinesisOperations::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(KinesisAsyncClient::class.java).size shouldBeEqualTo 1
                context.getBean(KinesisProperties::class.java).endpointOverride shouldBeEqualTo
                    URI.create("http://localhost:4566")
            }
    }
}
