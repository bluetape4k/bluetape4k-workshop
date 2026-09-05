package io.bluetape4k.workshop.graph.abuser

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecution
import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecutionObserver
import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecutionPath
import io.bluetape4k.graph.algo.provider.GraphAlgorithmFallbackReason
import io.bluetape4k.graph.algo.provider.GraphAlgorithmProviderPolicy
import io.bluetape4k.graph.algo.provider.GraphAlgorithmProviderUnavailableException
import io.bluetape4k.graph.model.PageRankScore
import io.bluetape4k.graph.model.graphVertexOf
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.workshop.graph.abuser.schema.UserLabel
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

class AbuserDetectionExecutionPolicyTest {

    @Test
    fun `AUTO는 provider가 없으면 호출 결과와 observer에 NO_PROVIDER를 남긴다`() {
        val ops = pageRankOperations()
        val observed = CopyOnWriteArrayList<GraphAlgorithmExecution>()
        val service = AbuserDetectionService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver { observed += it },
        )

        val ranking = service.rankSuspiciousUsersWithExecution(
            limit = 2,
            policy = GraphAlgorithmProviderPolicy.AUTO,
        )

        verify(exactly = 1) { ops.pageRank(match { it.topK == 2 }) }
        ranking.scores shouldHaveSize 1
        ranking.execution.path shouldBeEqualTo GraphAlgorithmExecutionPath.JVM_FALLBACK
        ranking.execution.fallbackReason shouldBeEqualTo GraphAlgorithmFallbackReason.NO_PROVIDER
        observed shouldHaveSize 1
        observed.single().fallbackReason shouldBeEqualTo GraphAlgorithmFallbackReason.NO_PROVIDER
    }

    @Test
    fun `JVM_ONLY는 PageRank를 한 번 실행하고 policy reason을 반환한다`() {
        val ops = pageRankOperations()
        val observed = CopyOnWriteArrayList<GraphAlgorithmExecution>()
        val service = AbuserDetectionService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver { observed += it },
        )

        val ranking = service.rankSuspiciousUsersWithExecution(
            limit = 2,
            policy = GraphAlgorithmProviderPolicy.JVM_ONLY,
        )

        verify(exactly = 1) { ops.pageRank(match { it.topK == 2 }) }
        ranking.execution.fallbackReason shouldBeEqualTo GraphAlgorithmFallbackReason.JVM_ONLY_POLICY
        observed shouldHaveSize 1
        observed.single() shouldBeEqualTo GraphAlgorithmExecution(
            algorithm = ranking.execution.algorithm,
            providerId = ranking.execution.providerId,
            path = ranking.execution.path,
            fallbackReason = ranking.execution.fallbackReason,
        )
    }

    @Test
    fun `NATIVE_ONLY는 provider가 없으면 PageRank 실행 전에 실패한다`() {
        val ops = pageRankOperations()
        val observed = CopyOnWriteArrayList<GraphAlgorithmExecution>()
        val service = AbuserDetectionService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver { observed += it },
        )

        assertFailsWith<GraphAlgorithmProviderUnavailableException> {
            service.rankSuspiciousUsersWithExecution(
                limit = 2,
                policy = GraphAlgorithmProviderPolicy.NATIVE_ONLY,
            )
        }

        verify(exactly = 0) { ops.pageRank(any()) }
        observed shouldHaveSize 0
    }

    @Test
    fun `일반 observer 실패는 PageRank 결과를 실패시키지 않는다`() {
        val ops = pageRankOperations()
        val service = AbuserDetectionService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver { throw IllegalStateException("sensitive-provider-detail") },
        )

        val ranking = service.rankSuspiciousUsersWithExecution(limit = 1)

        ranking.scores shouldHaveSize 1
        verify(exactly = 1) { ops.pageRank(any()) }
    }

    @Test
    fun `observer cancellation은 결과로 위장하지 않고 재전파한다`() {
        val ops = pageRankOperations()
        val service = AbuserDetectionService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver { throw CancellationException("cancel") },
        )

        assertFailsWith<CancellationException> {
            service.rankSuspiciousUsersWithExecution(limit = 1)
        }

        verify(exactly = 1) { ops.pageRank(any()) }
    }

    @Test
    fun `동시 호출의 실행 reason은 각 요청 policy에 귀속된다`() {
        val workers = 20
        val barrier = CyclicBarrier(workers)
        val score = pageRankScore()
        val ops = mockk<GraphOperations> {
            every { pageRank(any()) } answers {
                barrier.await()
                listOf(score)
            }
        }
        val observed = CopyOnWriteArrayList<GraphAlgorithmExecution>()
        val outputs = CopyOnWriteArrayList<Pair<GraphAlgorithmProviderPolicy, GraphAlgorithmFallbackReason?>>()
        val index = AtomicInteger(0)
        val service = AbuserDetectionService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver { observed += it },
        )

        MultithreadingTester()
            .workers(workers)
            .rounds(1)
            .add {
                val policy = if (index.getAndIncrement() % 2 == 0) {
                    GraphAlgorithmProviderPolicy.AUTO
                } else {
                    GraphAlgorithmProviderPolicy.JVM_ONLY
                }
                val result = service.rankSuspiciousUsersWithExecution(policy = policy)
                outputs += policy to result.execution.fallbackReason
            }
            .run()

        outputs shouldHaveSize workers
        outputs.forEach { (policy, reason) ->
            reason shouldBeEqualTo when (policy) {
                GraphAlgorithmProviderPolicy.AUTO -> GraphAlgorithmFallbackReason.NO_PROVIDER
                GraphAlgorithmProviderPolicy.JVM_ONLY -> GraphAlgorithmFallbackReason.JVM_ONLY_POLICY
                GraphAlgorithmProviderPolicy.NATIVE_ONLY -> error("테스트 입력에 없는 policy")
            }
        }
        observed shouldHaveSize workers
        verify(exactly = workers) { ops.pageRank(any()) }
    }

    private fun pageRankOperations(): GraphOperations = mockk {
        every { pageRank(any()) } returns listOf(pageRankScore())
    }

    private fun pageRankScore(): PageRankScore = PageRankScore(
        vertex = graphVertexOf("user-1", UserLabel.label),
        score = 0.75,
    )
}
