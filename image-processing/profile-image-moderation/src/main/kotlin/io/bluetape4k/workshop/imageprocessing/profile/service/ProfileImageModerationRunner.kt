package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationRequest
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import kotlin.time.toKotlinDuration

@Component
/**
 * 제한된 동시성과 타임아웃으로 검수 작업을 비동기로 실행합니다.
 * @property provider 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property repository 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property properties 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class ProfileImageModerationRunner(
    /**
     * `provider`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val provider: ImageModerationProvider,
    /**
     * `repository`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: ProfileImageRepository,
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ProfileImageModerationProperties,
    /**
     * `metrics`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: ProfileImageMetrics,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private companion object: KLogging()
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val semaphore = Semaphore(properties.moderationConcurrency)

    /**
     * schedule은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 이미지 업로드·검수·파생물 처리 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun schedule(request: ModerationRequest) {
        scope.launch {
            val started = System.nanoTime()
            var resultTag = "failure"
            try {
                semaphore.withPermit {
                    val result = withTimeout(properties.moderationTimeout.toKotlinDuration()) {
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
                val changed = repository.failModeration(
                    request.userId,
                    request.uploadId,
                    e.message ?: e.javaClass.simpleName
                )
                metrics.transition("failed", if (changed) "updated" else "stale")
                log.warn(e) { "Profile image moderation failed" }
            } finally {
                metrics.moderation(System.nanoTime() - started, resultTag)
            }
        }
    }

    /**
     * shutdown은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @PreDestroy
    fun shutdown() {
        scope.cancel("profile image moderation runner shutdown")
    }
}
