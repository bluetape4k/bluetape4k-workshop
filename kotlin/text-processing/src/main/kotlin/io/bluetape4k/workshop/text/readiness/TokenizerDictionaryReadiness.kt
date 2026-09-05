package io.bluetape4k.workshop.text.readiness

import io.bluetape4k.tokenizer.japanese.JapaneseProcessor
import io.bluetape4k.tokenizer.korean.KoreanProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Korean/Japanese dictionary preload의 현재 준비 상태입니다.
 */
enum class DictionaryReadinessStatus {
    NOT_READY,
    LOADING,
    READY,
}

/**
 * Dictionary preload 상태와 최근 attempt 번호를 담는 immutable 관찰 값입니다.
 *
 * 초기 `NOT_READY`만 attempt 0을 허용하고, preload가 시작된 상태는 1 이상의 attempt를
 * 사용합니다. 실패 원문이나 dictionary 경로는 이 값에 포함하지 않습니다.
 */
data class DictionaryReadinessSnapshot(
    val status: DictionaryReadinessStatus,
    val attempt: Long,
) {
    init {
        require(attempt >= 0) { "attempt must be non-negative." }
        if (status != DictionaryReadinessStatus.NOT_READY) {
            require(attempt > 0) { "$status requires a positive attempt." }
        }
    }

    companion object {
        fun notReady(attempt: Long = 0): DictionaryReadinessSnapshot =
            DictionaryReadinessSnapshot(DictionaryReadinessStatus.NOT_READY, attempt)

        fun loading(attempt: Long): DictionaryReadinessSnapshot =
            DictionaryReadinessSnapshot(DictionaryReadinessStatus.LOADING, attempt)

        fun ready(attempt: Long): DictionaryReadinessSnapshot =
            DictionaryReadinessSnapshot(DictionaryReadinessStatus.READY, attempt)
    }
}

/**
 * Readiness gate가 반환하는 명시적인 요청 결과입니다.
 */
sealed interface DictionaryReadyResult<out T> {
    val readiness: DictionaryReadinessSnapshot

    data class Ready<T>(
        val value: T,
        override val readiness: DictionaryReadinessSnapshot,
    ): DictionaryReadyResult<T>

    data class NotReady(
        override val readiness: DictionaryReadinessSnapshot,
    ): DictionaryReadyResult<Nothing>
}

/**
 * Korean/Japanese processor의 suspend preload를 하나의 readiness lifecycle로 결속합니다.
 *
 * [preload]는 concurrent caller 사이에서 성공 값을 공유합니다. 첫 caller가 실패하거나
 * 취소되면 상태를 [DictionaryReadinessStatus.NOT_READY]로 되돌리고 원래 throwable을
 * 재전파하므로 다음 caller가 새 attempt로 재시도할 수 있습니다.
 *
 * 애플리케이션은 startup 단계에서 [preload]를 호출한 뒤 기존 multilingual index를 만들고,
 * 요청 경로에서는 [runWhenReady]로 준비되지 않은 tokenizer 실행을 차단할 수 있습니다.
 */
class TokenizerDictionaryReadiness(
    private val preloadKorean: suspend () -> Unit = { KoreanProcessor.preload() },
    private val preloadJapanese: suspend () -> Unit = { JapaneseProcessor.preload() },
) {
    private val preloadMutex = Mutex()

    @Volatile
    private var snapshot: DictionaryReadinessSnapshot = DictionaryReadinessSnapshot.notReady()

    /** 현재 immutable readiness 값을 반환합니다. */
    fun current(): DictionaryReadinessSnapshot = snapshot

    /**
     * 두 processor dictionary를 순서대로 preload하고 공유된 `READY` 값을 반환합니다.
     */
    suspend fun preload(): DictionaryReadinessSnapshot = preloadMutex.withLock {
        if (snapshot.status == DictionaryReadinessStatus.READY) {
            return@withLock snapshot
        }

        val loading = DictionaryReadinessSnapshot.loading(snapshot.attempt + 1)
        snapshot = loading
        try {
            preloadKorean()
            preloadJapanese()
            currentCoroutineContext().ensureActive()
            DictionaryReadinessSnapshot.ready(loading.attempt).also { snapshot = it }
        } catch (e: CancellationException) {
            snapshot = DictionaryReadinessSnapshot.notReady(loading.attempt)
            throw e
        } catch (e: Throwable) {
            snapshot = DictionaryReadinessSnapshot.notReady(loading.attempt)
            throw e
        }
    }

    /**
     * 현재 상태가 `READY`일 때만 [block]을 실행합니다.
     */
    suspend fun <T> runWhenReady(block: suspend () -> T): DictionaryReadyResult<T> {
        val observed = snapshot
        return if (observed.status == DictionaryReadinessStatus.READY) {
            DictionaryReadyResult.Ready(block(), observed)
        } else {
            DictionaryReadyResult.NotReady(observed)
        }
    }
}
