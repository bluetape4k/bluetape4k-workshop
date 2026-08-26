package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class KafkaFailoverImageContractTest {

    @Test
    fun `approved image reference is immutable and digest qualified`() {
        KafkaFailoverClusterFixture.APPROVED_IMAGE_DIGEST.matches(Regex("[0-9a-f]{64}")).shouldBeTrue()
        KafkaFailoverClusterFixture.IMAGE_REFERENCE shouldBeEqualTo
            "apache/kafka@sha256:${KafkaFailoverClusterFixture.APPROVED_IMAGE_DIGEST}"

        KafkaFailoverClusterFixture.validateRepoDigests(
            listOf(KafkaFailoverClusterFixture.IMAGE_REFERENCE),
        )
    }

    @Test
    fun `missing multiple tag-only and mismatched repo digests fail closed`() {
        val invalid = listOf(
            emptyList(),
            listOf(KafkaFailoverClusterFixture.IMAGE_REFERENCE, KafkaFailoverClusterFixture.IMAGE_REFERENCE),
            listOf("apache/kafka:4.2.0"),
            listOf("other/kafka@sha256:${KafkaFailoverClusterFixture.APPROVED_IMAGE_DIGEST}"),
            listOf("apache/kafka@sha256:${"0".repeat(64)}"),
        )

        invalid.forEach { repoDigests ->
            assertFailsWith<IllegalArgumentException> {
                KafkaFailoverClusterFixture.validateRepoDigests(repoDigests)
            }
        }
    }
}
