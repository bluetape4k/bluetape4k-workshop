package io.bluetape4k.workshop.text.readiness

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendDefault
import io.bluetape4k.workshop.text.search.CoroutineMultilingualSearchIndex
import io.bluetape4k.workshop.text.search.MultilingualSearchIndex
import io.bluetape4k.workshop.text.search.SearchDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TokenizerDictionaryReadinessTest {

    @Test
    fun `readiness 전에는 요청 block을 실행하지 않는다`() = runSuspendDefault {
        val readiness = testReadiness()
        var invoked = false

        val result = readiness.runWhenReady {
            invoked = true
            "partial-result"
        }

        invoked.shouldBeFalse()
        (result is DictionaryReadyResult.NotReady).shouldBeTrue()
        result.readiness shouldBeEqualTo DictionaryReadinessSnapshot.notReady()
    }

    @Test
    fun `동시 preload 호출은 두 loader를 한 번만 공유한다`() = runSuspendDefault {
        val koreanCalls = AtomicInteger()
        val japaneseCalls = AtomicInteger()
        val koreanEntered = CompletableDeferred<Unit>()
        val releaseKorean = CompletableDeferred<Unit>()
        val readiness = TokenizerDictionaryReadiness(
            preloadKorean = {
                koreanCalls.incrementAndGet()
                koreanEntered.complete(Unit)
                releaseKorean.await()
            },
            preloadJapanese = { japaneseCalls.incrementAndGet() },
        )

        val snapshots = coroutineScope {
            val callers = List(16) { async { readiness.preload() } }
            koreanEntered.await()
            readiness.current().status shouldBeEqualTo DictionaryReadinessStatus.LOADING
            var requestInvoked = false
            val loadingResult = readiness.runWhenReady {
                requestInvoked = true
                "partial-result"
            }
            requestInvoked.shouldBeFalse()
            (loadingResult is DictionaryReadyResult.NotReady).shouldBeTrue()
            loadingResult.readiness.status shouldBeEqualTo DictionaryReadinessStatus.LOADING
            releaseKorean.complete(Unit)
            callers.awaitAll()
        }

        koreanCalls.get() shouldBeEqualTo 1
        japaneseCalls.get() shouldBeEqualTo 1
        snapshots.all { it === snapshots.first() }.shouldBeTrue()
        snapshots.first() shouldBeEqualTo DictionaryReadinessSnapshot.ready(attempt = 1)
    }

    @Test
    fun `preload 취소는 NOT_READY로 복귀하고 다음 attempt에서 재시도한다`() = runSuspendDefault {
        val koreanCalls = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val readiness = TokenizerDictionaryReadiness(
            preloadKorean = {
                if (koreanCalls.incrementAndGet() == 1) {
                    firstEntered.complete(Unit)
                    awaitCancellation()
                }
            },
            preloadJapanese = {},
        )
        val first = launch { readiness.preload() }
        firstEntered.await()

        first.cancelAndJoin()

        first.isCancelled.shouldBeTrue()
        readiness.current() shouldBeEqualTo DictionaryReadinessSnapshot.notReady(attempt = 1)
        readiness.preload() shouldBeEqualTo DictionaryReadinessSnapshot.ready(attempt = 2)
        koreanCalls.get() shouldBeEqualTo 2
    }

    @Test
    fun `loader가 cancellation을 삼켜도 READY를 공개하지 않는다`() = runSuspendDefault {
        val koreanCalls = AtomicInteger()
        val japaneseCalls = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val readiness = TokenizerDictionaryReadiness(
            preloadKorean = {
                koreanCalls.incrementAndGet()
                firstEntered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    // 잘못된 loader가 cancellation을 삼키는 경계도 coordinator가 방어해야 합니다.
                }
            },
            preloadJapanese = { japaneseCalls.incrementAndGet() },
        )
        val first = launch { readiness.preload() }
        firstEntered.await()

        first.cancelAndJoin()

        first.isCancelled.shouldBeTrue()
        readiness.current() shouldBeEqualTo DictionaryReadinessSnapshot.notReady(attempt = 1)
        koreanCalls.get() shouldBeEqualTo 1
        japaneseCalls.get() shouldBeEqualTo 1
    }

    @Test
    fun `preload 실패는 원래 예외를 전파하고 retry 성공을 허용한다`() = runSuspendDefault {
        val failFirst = AtomicBoolean(true)
        val expected = IllegalStateException("synthetic dictionary failure")
        val readiness = TokenizerDictionaryReadiness(
            preloadKorean = {
                if (failFirst.getAndSet(false)) throw expected
            },
            preloadJapanese = {},
        )

        val actual = assertFailsWith<IllegalStateException> { readiness.preload() }

        actual shouldBeSameInstanceAs expected
        readiness.current() shouldBeEqualTo DictionaryReadinessSnapshot.notReady(attempt = 1)
        readiness.preload() shouldBeEqualTo DictionaryReadinessSnapshot.ready(attempt = 2)
    }

    @Test
    fun `실제 preload 뒤 기존 sync와 coroutine index 결과가 같다`() = runSuspendDefault {
        val documents = multilingualDocuments()
        val readiness = TokenizerDictionaryReadiness()

        readiness.preload()
        val syncIndex = MultilingualSearchIndex.indexOf(documents)
        val coroutineIndex = CoroutineMultilingualSearchIndex.indexOf(documents)
        val result = readiness.runWhenReady { coroutineIndex.search("서울 카페") }

        (result is DictionaryReadyResult.Ready).shouldBeTrue()
        val ready = result as DictionaryReadyResult.Ready
        ready.value shouldBeEqualTo syncIndex.search("서울 카페")
        ready.readiness shouldBeEqualTo DictionaryReadinessSnapshot.ready(attempt = 1)
    }

    @Test
    fun `snapshot은 유효하지 않은 상태와 attempt 조합을 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            DictionaryReadinessSnapshot(DictionaryReadinessStatus.READY, attempt = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            DictionaryReadinessSnapshot(DictionaryReadinessStatus.NOT_READY, attempt = -1)
        }
    }

    private fun testReadiness(): TokenizerDictionaryReadiness =
        TokenizerDictionaryReadiness(preloadKorean = {}, preloadJapanese = {})

    private fun multilingualDocuments(): List<SearchDocument> = listOf(
        SearchDocument.of("ko-1", "서울 카페", "서울 카페 예약 문서입니다."),
        SearchDocument.of("ja-1", "東京 観光", "東京の観光案内です。"),
    )
}
