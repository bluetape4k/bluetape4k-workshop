package io.bluetape4k.workshop.aws.kinesis

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import java.net.URI
import java.time.Duration
import java.util.stream.Stream
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KinesisWorkshopPropertiesTest {

    @Test
    fun `defaults to credential free local profile and demo`() {
        val properties = KinesisWorkshopProperties()

        properties.profile shouldBeEqualTo "local"
        properties.runDemo shouldBeEqualTo true
        properties.batchLimit shouldBeEqualTo 100
        properties.pollInterval shouldBeEqualTo Duration.ofMillis(200)
        properties.emptyBackoff shouldBeEqualTo Duration.ofSeconds(1)
        properties.maxAggregatePayloadBytes shouldBeEqualTo 1L * 1024 * 1024
    }

    @Test
    fun `rejects poll intervals below upstream safe floor`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisWorkshopProperties(pollInterval = Duration.ofMillis(199))
        }
    }

    @Test
    fun `rejects non-positive empty response backoff`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisWorkshopProperties(emptyBackoff = Duration.ZERO)
        }
    }

    @Test
    fun `rejects batch and aggregate payload limits outside workshop bounds`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisWorkshopProperties(batchLimit = 1_001)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisWorkshopProperties(maxAggregatePayloadBytes = 1L * 1024 * 1024 + 1)
        }
    }

    @Test
    fun `requires positive retry budgets for bound properties`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisWorkshopProperties(maxIteratorRetries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            KinesisWorkshopProperties(maxThrottleRetries = 0)
        }
    }

    @Test
    fun `accepts loopback and local service endpoints`() {
        listOf(
            URI.create("http://localhost:4566"),
            URI.create("http://127.0.0.1:4566"),
            URI.create("http://localstack:4566"),
            URI.create("https://kinesis:8443"),
        ).forEach { endpoint ->
            KinesisWorkshopProperties(endpoint = endpoint).endpoint shouldBeEqualTo endpoint
        }
    }

    @Test
    fun `rejects unsafe endpoint without echoing the URI`() {
        val unsafeEndpoint = "http://user:secret@169.254.169.254/latest/meta-data"

        val error = assertFailsWith<IllegalArgumentException> {
            KinesisWorkshopProperties(endpoint = URI.create(unsafeEndpoint))
        }

        error.message.orEmpty() shouldContain "endpoint"
        error.message.orEmpty() shouldNotContain unsafeEndpoint
        error.message.orEmpty() shouldNotContain "secret"
    }

    @ParameterizedTest(name = "rejects endpoint {0}")
    @MethodSource("unsafeEndpoints")
    fun `rejects non loopback or non HTTP endpoint without echoing input`(endpointValue: String) {
        val error = assertFailsWith<IllegalArgumentException> {
            KinesisWorkshopProperties(endpoint = URI.create(endpointValue))
        }

        error.message.orEmpty() shouldContain "endpoint"
        error.message.orEmpty() shouldNotContain endpointValue
        error.message.orEmpty() shouldNotContain "sentinel"
    }

    @Test
    fun `requires explicit real aws inputs`() {
        assertFailsWith<IllegalArgumentException> {
            KinesisWorkshopProperties(
                profile = "real-aws",
                streamName = " ",
                partitionKey = "partition",
                shardId = "shardId-000000000000",
            )
        }
    }

    companion object {
        @JvmStatic
        fun unsafeEndpoints(): Stream<Arguments> = Stream.of(
            Arguments.of("ftp://localhost:4566/sentinel"),
            Arguments.of("http://169.254.169.254/latest/sentinel"),
            Arguments.of("http://10.0.0.9:4566/sentinel"),
            Arguments.of("http://172.16.0.9:4566/sentinel"),
            Arguments.of("http://192.168.0.9:4566/sentinel"),
            Arguments.of("http://user:secret@localhost:4566/sentinel"),
        )
    }
}
