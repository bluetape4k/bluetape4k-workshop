package io.bluetape4k.workshop.text.detection

import com.github.pemistahl.lingua.api.Language
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [LanguageDetectionService] 를 coroutine 친화적으로 보호하는 guard 입니다.
 *
 * ## Behavior / Contract
 * - Lingua detection 작업에는 caller 가 제공한 [dispatcher] 를 사용합니다.
 * - [Mutex] 로 감싼 detector 접근을 직렬화하므로 detector 를 직접 노출하지 않고도 하나의 wrapper 를 여러 coroutine caller 가 재사용할 수 있습니다.
 * - caller 를 blocking 하지 않고 suspend function 을 사용해 structured cancellation 을 유지합니다.
 *
 * ```kotlin
 * val detection = CoroutineLanguageDetectionService()
 * val language = detection.detectLanguage("서울 카페 예약")
 * ```
 */
class CoroutineLanguageDetectionService(
    private val delegate: LanguageDetectionService = LanguageDetectionService(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val mutex = Mutex()

    /**
     * 보호된 detector 로 [text] 에 가장 가능성이 높은 언어를 감지합니다.
     */
    suspend fun detectLanguage(text: String): Language? =
        withContext(dispatcher) {
            mutex.withLock {
                delegate.detectLanguage(text)
            }
        }

    /**
     * 보호된 detector 로 [text] 의 언어별 confidence value 를 계산합니다.
     */
    suspend fun computeConfidenceValues(text: String): Map<Language, Double> =
        withContext(dispatcher) {
            mutex.withLock {
                delegate.computeConfidenceValues(text)
            }
        }
}
