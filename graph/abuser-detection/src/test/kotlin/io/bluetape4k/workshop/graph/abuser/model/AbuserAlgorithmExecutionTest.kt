package io.bluetape4k.workshop.graph.abuser.model

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecution
import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecutionPath
import io.bluetape4k.graph.algo.provider.GraphAlgorithmFallbackReason
import io.bluetape4k.graph.algo.provider.GraphAlgorithmId
import org.junit.jupiter.api.Test

class AbuserAlgorithmExecutionTest {

    @Test
    fun `provider ID는 안전한 64자 경계만 허용한다`() {
        val execution = AbuserAlgorithmExecution(
            algorithm = GraphAlgorithmId.PAGE_RANK,
            providerId = "a".repeat(64),
            path = GraphAlgorithmExecutionPath.JVM_FALLBACK,
            fallbackReason = GraphAlgorithmFallbackReason.NO_PROVIDER,
        )

        execution.providerId shouldBeEqualTo "a".repeat(64)
        listOf("bad\r\nid", "bad\u0000id", "bad\tid", "A-provider", "x".repeat(65)).forEach { providerId ->
            assertFailsWith<IllegalArgumentException> {
                AbuserAlgorithmExecution(
                    algorithm = GraphAlgorithmId.PAGE_RANK,
                    providerId = providerId,
                    path = GraphAlgorithmExecutionPath.JVM_FALLBACK,
                    fallbackReason = GraphAlgorithmFallbackReason.NO_PROVIDER,
                )
            }
        }
    }

    @Test
    fun `native와 fallback 실행 경로의 reason 불변식을 검증한다`() {
        AbuserAlgorithmExecution(
            algorithm = GraphAlgorithmId.PAGE_RANK,
            providerId = "native-provider",
            path = GraphAlgorithmExecutionPath.NATIVE,
            fallbackReason = null,
        )
        AbuserAlgorithmExecution(
            algorithm = GraphAlgorithmId.PAGE_RANK,
            providerId = "jvm-fallback",
            path = GraphAlgorithmExecutionPath.JVM_FALLBACK,
            fallbackReason = GraphAlgorithmFallbackReason.NO_PROVIDER,
        )

        assertFailsWith<IllegalArgumentException> {
            AbuserAlgorithmExecution(
                algorithm = GraphAlgorithmId.PAGE_RANK,
                providerId = "native-provider",
                path = GraphAlgorithmExecutionPath.NATIVE,
                fallbackReason = GraphAlgorithmFallbackReason.NO_PROVIDER,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AbuserAlgorithmExecution(
                algorithm = GraphAlgorithmId.PAGE_RANK,
                providerId = "jvm-fallback",
                path = GraphAlgorithmExecutionPath.JVM_FALLBACK,
                fallbackReason = null,
            )
        }
    }

    @Test
    fun `upstream 실행 결과를 bounded 모델로 투영한다`() {
        val upstream = GraphAlgorithmExecution(
            algorithm = GraphAlgorithmId.PAGE_RANK,
            providerId = "jvm-fallback",
            path = GraphAlgorithmExecutionPath.JVM_FALLBACK,
            fallbackReason = GraphAlgorithmFallbackReason.JVM_ONLY_POLICY,
        )

        AbuserAlgorithmExecution.from(upstream) shouldBeEqualTo AbuserAlgorithmExecution(
            algorithm = GraphAlgorithmId.PAGE_RANK,
            providerId = "jvm-fallback",
            path = GraphAlgorithmExecutionPath.JVM_FALLBACK,
            fallbackReason = GraphAlgorithmFallbackReason.JVM_ONLY_POLICY,
        )
    }
}
