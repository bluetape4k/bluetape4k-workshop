package io.bluetape4k.workshop.text.detection

import com.github.pemistahl.lingua.api.Language
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coroutine-friendly guard around [LanguageDetectionService].
 *
 * ## Behavior / Contract
 * - Uses the caller-provided [dispatcher] for Lingua detection work.
 * - Serializes access to the wrapped detector with a [Mutex], so one shared wrapper can be reused
 *   by concurrent coroutine callers without exposing the detector directly.
 * - Keeps cancellation structured by using suspend functions instead of blocking callers.
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
     * Detects the most likely language for [text] using the guarded detector.
     */
    suspend fun detectLanguage(text: String): Language? =
        withContext(dispatcher) {
            mutex.withLock {
                delegate.detectLanguage(text)
            }
        }

    /**
     * Computes confidence values for [text] using the guarded detector.
     */
    suspend fun computeConfidenceValues(text: String): Map<Language, Double> =
        withContext(dispatcher) {
            mutex.withLock {
                delegate.computeConfidenceValues(text)
            }
        }
}
