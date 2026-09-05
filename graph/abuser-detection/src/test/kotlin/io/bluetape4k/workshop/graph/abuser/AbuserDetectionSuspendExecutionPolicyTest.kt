package io.bluetape4k.workshop.graph.abuser

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecution
import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecutionObserver
import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecutionPath
import io.bluetape4k.graph.algo.provider.GraphAlgorithmFallbackReason
import io.bluetape4k.graph.algo.provider.GraphAlgorithmProviderPolicy
import io.bluetape4k.graph.algo.provider.GraphAlgorithmProviderUnavailableException
import io.bluetape4k.graph.model.PageRankScore
import io.bluetape4k.graph.model.graphVertexOf
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.graph.abuser.schema.UserLabel
import io.bluetape4k.workshop.graph.abuser.service.AbuserDetectionSuspendService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AbuserDetectionSuspendExecutionPolicyTest {

    @Test
    fun `AUTO는 suspend 결과와 observer에 NO_PROVIDER를 남긴다`() = runSuspendIO {
        val ops = pageRankOperations()
        val observed = CopyOnWriteArrayList<GraphAlgorithmExecution>()
        val service = AbuserDetectionSuspendService(
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
    }

    @Test
    fun `JVM_ONLY는 suspend PageRank를 한 번 수집하고 policy reason을 반환한다`() = runSuspendIO {
        val ops = pageRankOperations()
        val service = AbuserDetectionSuspendService(ops, "test")

        val ranking = service.rankSuspiciousUsersWithExecution(
            limit = 2,
            policy = GraphAlgorithmProviderPolicy.JVM_ONLY,
        )

        verify(exactly = 1) { ops.pageRank(match { it.topK == 2 }) }
        ranking.execution.fallbackReason shouldBeEqualTo GraphAlgorithmFallbackReason.JVM_ONLY_POLICY
        ranking.scores.single().rank shouldBeEqualTo 1
    }

    @Test
    fun `NATIVE_ONLY는 provider가 없으면 suspend PageRank 실행 전에 실패한다`() = runSuspendIO {
        val ops = pageRankOperations()
        val service = AbuserDetectionSuspendService(ops, "test")

        assertFailsWith<GraphAlgorithmProviderUnavailableException> {
            service.rankSuspiciousUsersWithExecution(
                limit = 2,
                policy = GraphAlgorithmProviderPolicy.NATIVE_ONLY,
            )
        }

        verify(exactly = 0) { ops.pageRank(any()) }
    }

    @Test
    fun `수집 중 취소되면 observer를 호출하지 않고 결과도 반환하지 않는다`() = runSuspendIO {
        val firstEmission = CompletableDeferred<Unit>()
        val ops = mockk<GraphSuspendOperations> {
            every { pageRank(any()) } returns flow {
                firstEmission.complete(Unit)
                emit(pageRankScore())
                awaitCancellation()
            }
        }
        val observed = CopyOnWriteArrayList<GraphAlgorithmExecution>()
        val service = AbuserDetectionSuspendService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver { observed += it },
        )

        val job = launch { service.rankSuspiciousUsersWithExecution() }
        firstEmission.await()
        job.cancelAndJoin()

        observed.shouldBeEmpty()
        job.isCancelled.shouldBeTrue()
        verify(exactly = 1) { ops.pageRank(any()) }
    }

    @Test
    fun `일반 observer 실패는 suspend 결과를 실패시키지 않는다`() = runSuspendIO {
        val ops = pageRankOperations()
        val service = AbuserDetectionSuspendService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver { throw IllegalStateException("sensitive-provider-detail") },
        )

        service.rankSuspiciousUsersWithExecution(limit = 1).scores shouldHaveSize 1
    }

    @Test
    fun `observer cancellation은 suspend 호출에서 재전파한다`() = runSuspendIO {
        val ops = pageRankOperations()
        val service = AbuserDetectionSuspendService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver { throw CancellationException("cancel") },
        )

        assertFailsWith<CancellationException> {
            service.rankSuspiciousUsersWithExecution(limit = 1)
        }
    }

    @Test
    fun `observer callback 중 취소되면 event는 최대 한 번이고 결과는 반환하지 않는다`() = runSuspendIO {
        val ops = pageRankOperations()
        val observerStarted = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val observed = AtomicInteger(0)
        val returned = AtomicReference<Any?>()
        val service = AbuserDetectionSuspendService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver {
                observed.incrementAndGet()
                observerStarted.countDown()
                check(releaseObserver.await(5, TimeUnit.SECONDS))
            },
        )

        val job = launch(Dispatchers.Default) {
            returned.set(service.rankSuspiciousUsersWithExecution())
        }
        observerStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        job.cancel()
        releaseObserver.countDown()
        job.join()

        observed.get() shouldBeEqualTo 1
        returned.get().shouldBeNull()
        job.isCancelled.shouldBeTrue()
    }

    @Test
    fun `동시 suspend 호출의 실행 reason은 각 요청 policy에 귀속된다`() = runSuspendIO {
        val calls = 20
        val started = AtomicInteger(0)
        val allStarted = CompletableDeferred<Unit>()
        val ops = mockk<GraphSuspendOperations> {
            every { pageRank(any()) } answers {
                flow {
                    if (started.incrementAndGet() == calls) {
                        allStarted.complete(Unit)
                    }
                    allStarted.await()
                    emit(pageRankScore())
                }
            }
        }
        val observed = CopyOnWriteArrayList<GraphAlgorithmExecution>()
        val service = AbuserDetectionSuspendService(
            ops,
            "test",
            GraphAlgorithmExecutionObserver { observed += it },
        )

        val outputs = coroutineScope {
            List(calls) { index ->
                async {
                    val policy = if (index % 2 == 0) {
                        GraphAlgorithmProviderPolicy.AUTO
                    } else {
                        GraphAlgorithmProviderPolicy.JVM_ONLY
                    }
                    policy to service.rankSuspiciousUsersWithExecution(policy = policy).execution.fallbackReason
                }
            }.awaitAll()
        }

        outputs shouldHaveSize calls
        outputs.forEach { (policy, reason) ->
            reason shouldBeEqualTo when (policy) {
                GraphAlgorithmProviderPolicy.AUTO -> GraphAlgorithmFallbackReason.NO_PROVIDER
                GraphAlgorithmProviderPolicy.JVM_ONLY -> GraphAlgorithmFallbackReason.JVM_ONLY_POLICY
                GraphAlgorithmProviderPolicy.NATIVE_ONLY -> error("테스트 입력에 없는 policy")
            }
        }
        observed shouldHaveSize calls
        verify(exactly = calls) { ops.pageRank(any()) }
    }

    private fun pageRankOperations(): GraphSuspendOperations = mockk {
        every { pageRank(any()) } returns flowOf(pageRankScore())
    }

    private fun pageRankScore(): PageRankScore = PageRankScore(
        vertex = graphVertexOf("user-1", UserLabel.label),
        score = 0.75,
    )
}
