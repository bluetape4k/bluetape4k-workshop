package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationRequest
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

@Component
/**
 * 제한된 동시성과 타임아웃으로 검수 작업을 비동기로 실행합니다.
 */
class ProfileImageModerationRunner(
    private val provider: ImageModerationProvider,
    private val repository: ProfileImageRepository,
    private val properties: ProfileImageModerationProperties,
    private val metrics: ProfileImageMetrics,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val semaphore = Semaphore(properties.moderationConcurrency)

    fun schedule(request: ModerationRequest) {
        scope.launch {
            val started = System.nanoTime()
            var resultTag = "failure"
            try {
                semaphore.withPermit {
                    val result = withTimeout(properties.moderationTimeout.toMillis()) {
                        provider.moderate(request)
                    }
                    val changed = repository.completeModeration(request.userId, request.uploadId, result)
                    resultTag = if (changed) result.decision.name.lowercase() else "stale"
                    metrics.transition(result.decision.name.lowercase(), resultTag)
                }
            } catch (e: TimeoutCancellationException) {
                resultTag = "timeout"
                val changed = repository.failModeration(request.userId, request.uploadId, "moderation timed out")
                metrics.transition("timeout", if (changed) "updated" else "stale")
                log.warn(e) { "Profile image moderation timed out" }
            } catch (e: CancellationException) {
                resultTag = "cancelled"
                throw e
            } catch (e: Exception) {
                resultTag = "failed"
                val changed = repository.failModeration(request.userId, request.uploadId, e.message ?: e.javaClass.simpleName)
                metrics.transition("failed", if (changed) "updated" else "stale")
                log.warn(e) { "Profile image moderation failed" }
            } finally {
                metrics.moderation(System.nanoTime() - started, resultTag)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel("profile image moderation runner shutdown")
    }

    companion object : KLogging()
}
