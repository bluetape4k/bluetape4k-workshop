package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireLt
import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

@ConsistentCopyVisibility
/**
 * HighContentionDeadlines 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionDeadlines private constructor(
/**
 * absoluteProfileDeadlineNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val absoluteProfileDeadlineNanos: Long,
/**
 * profileExecutionDeadlineNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val profileExecutionDeadlineNanos: Long,
/**
 * cleanupDeadlineNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val cleanupDeadlineNanos: Long,
/**
 * runExecutionDeadlineNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val runExecutionDeadlineNanos: Long,
/**
 * nanoTime은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val nanoTime: () -> Long,
) {

/**
 * effectivePhaseBudget은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun effectivePhaseBudget(configuredPhaseBudget: Duration): Duration {
        val configuredNanos = configuredPhaseBudget.positiveNanos("configuredPhaseBudget")
        val now = nanoTime()
        val remainingProfileExecution = (profileExecutionDeadlineNanos - now).coerceAtLeast(0)
        val remainingRunExecution = (runExecutionDeadlineNanos - now).coerceAtLeast(0)
        return Duration.ofNanos(
            min(
                configuredNanos,
                min(remainingProfileExecution, remainingRunExecution),
            ),
        )
    }

    companion object {
/**
 * start은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun start(
            profileDeadline: Duration,
            cleanupReserve: Duration,
            reportFinalizeReserve: Duration,
            cleanupActionBudgets: List<Duration>,
            runExecutionDeadlineNanos: Long,
            nanoTime: () -> Long,
        ): HighContentionDeadlines {
/**
 * profileDeadlineNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val profileDeadlineNanos = profileDeadline.positiveNanos("profileDeadline")
/**
 * cleanupReserveNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val cleanupReserveNanos = cleanupReserve.positiveNanos("cleanupReserve")
/**
 * reportFinalizeReserveNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val reportFinalizeReserveNanos = reportFinalizeReserve.positiveNanos("reportFinalizeReserve")
/**
 * cleanupActionBudgetNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val cleanupActionBudgetNanos = cleanupActionBudgets.fold(0L) { total, budget ->
                Math.addExact(total, budget.positiveNanos("cleanupActionBudget"))
            }
            cleanupReserveNanos.requireGe(
                Math.addExact(
                    cleanupActionBudgetNanos,
                    reportFinalizeReserveNanos,
                ),
                "cleanupReserve",
            )
            cleanupReserveNanos.requireLt(profileDeadlineNanos, "cleanupReserve")
/**
 * startedAt은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val startedAt = nanoTime()
/**
 * absoluteProfileDeadline은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val absoluteProfileDeadline = Math.addExact(startedAt, profileDeadlineNanos)
            return HighContentionDeadlines(
                absoluteProfileDeadlineNanos = absoluteProfileDeadline,
                profileExecutionDeadlineNanos = absoluteProfileDeadline - cleanupReserveNanos,
                cleanupDeadlineNanos = absoluteProfileDeadline - reportFinalizeReserveNanos,
                runExecutionDeadlineNanos = runExecutionDeadlineNanos,
                nanoTime = nanoTime,
            )
        }
    }
}

/**
 * `HighContentionResourceState`은 수명주기 상태입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
enum class HighContentionResourceState {
    /**
     * `HighContentionResourceState.ALLOCATED`은 사용 가능한 자원이 특정 요청에 배정되어 다른 요청이 선점할 수 없는 값입니다.
     */
    ALLOCATED,
    /**
     * `HighContentionResourceState.STARTING`은 `starting` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal
     * 여부를 확인합니다.
     */
    STARTING,
    /**
     * `HighContentionResourceState.STARTED`은 실행 단위가 생성되어 worker가 선택할 수 있는 값입니다.
     */
    STARTED,
    /**
     * `HighContentionResourceState.CLOSED`은 신규 요청을 받지 않고 이미 생성된 작업만 마무리하는 종료 상태입니다.
     */
    CLOSED,
    /**
     * `HighContentionResourceState.CLOSE_FAILED`은 `close failed` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와
     * terminal 여부를 확인합니다.
     */
    CLOSE_FAILED,
}

/**
 * HighContentionResourceTransition 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class HighContentionResourceTransition(
/**
 * resourceKey은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val resourceKey: String,
/**
 * state은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val state: HighContentionResourceState,
)

/**
 * HighContentionCleanupException 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionCleanupException(
/**
 * resourceKey은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val resourceKey: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * HighContentionResource 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionResource internal constructor(
/**
 * id은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    internal val id: Long,
)

/**
 * HighContentionLifecycle 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class HighContentionLifecycle(
/**
 * cleanupDeadlineNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val cleanupDeadlineNanos: Long,
/**
 * nanoTime은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val nanoTime: () -> Long,
/**
 * transitionObserver은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val transitionObserver: (HighContentionResourceTransition) -> Unit = {},
) : AutoCloseable {

/**
 * nextResourceId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val nextResourceId = AtomicLong()
/**
 * resources은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val resources = mutableListOf<ResourceEntry>()
/**
 * failOpenBarriers은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val failOpenBarriers = mutableListOf<() -> Unit>()
/**
 * cleanupThreads은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val cleanupThreads = mutableListOf<Thread>()
/**
 * closed은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var closed = false

    @Synchronized
/**
 * allocate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun allocate(
        resourceKey: String,
        cleanupBudget: Duration,
        closeAction: () -> Unit,
    ): HighContentionResource {
        check(!closed) { "lifecycle is closed" }
/**
 * entry은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val entry = ResourceEntry(
            id = nextResourceId.getAndIncrement(),
            resourceKey = HighContentionArtifactPaths.requireIdentifier(resourceKey, "resourceKey"),
            cleanupBudgetNanos = cleanupBudget.positiveNanos("cleanupBudget"),
            closeAction = closeAction,
        )
        resources += entry
        transition(entry, HighContentionResourceState.ALLOCATED)
        return HighContentionResource(entry.id)
    }

    @Synchronized
/**
 * start은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun start(
        resource: HighContentionResource,
        startAction: () -> Unit,
    ) {
        check(!closed) { "lifecycle is closed" }
/**
 * entry은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val entry = resources.singleOrNull { it.id == resource.id }
            ?: throw IllegalArgumentException("resource does not belong to this lifecycle")
        check(entry.state == HighContentionResourceState.ALLOCATED) {
            "resource must be ALLOCATED before start"
        }
        transition(entry, HighContentionResourceState.STARTING)
        startAction()
        transition(entry, HighContentionResourceState.STARTED)
    }

    @Synchronized
/**
 * registerFailOpenBarrier은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun registerFailOpenBarrier(release: () -> Unit) {
        check(!closed) { "lifecycle is closed" }
        failOpenBarriers += release
    }

/**
 * close은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        finish()
    }

    @Synchronized
/**
 * finish은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun finish(primaryFailure: Throwable? = null) {
        if (closed) {
            if (primaryFailure != null) {
                throw primaryFailure
            }
            return
        }
        closed = true
/**
 * failures은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val failures = mutableListOf<Throwable>()
/**
 * interrupted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var interrupted = Thread.currentThread().isInterrupted
        if (interrupted) {
            Thread.interrupted()
        }

        try {
            failOpenBarriers.asReversed().forEachIndexed { index, release ->
                try {
                    release()
                } catch (error: Throwable) {
                    failures += HighContentionCleanupException(
                        resourceKey = "fail-open-barrier-$index",
                        message = "fail-open barrier release failed",
                        cause = error,
                    )
                }
            }
            resources.asReversed().forEach { resource ->
/**
 * result은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val result = closeResource(resource)
                interrupted = interrupted || result.interrupted
                result.failure?.let(failures::add)
            }
            if (liveCleanupThreadNames().isNotEmpty()) {
                failures += HighContentionCleanupException(
                    resourceKey = "cleanup-thread-leak",
                    message = "cleanup completed with a live cleanup thread",
                )
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }

        if (primaryFailure != null) {
            failures.forEach(primaryFailure::addSuppressed)
            throw primaryFailure
        }
/**
 * first은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val first = failures.firstOrNull() ?: return
        failures.drop(1).forEach(first::addSuppressed)
        throw first
    }

    @Synchronized
/**
 * liveCleanupThreadNames은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun liveCleanupThreadNames(): List<String> =
        cleanupThreads
            .filter(Thread::isAlive)
            .map(Thread::getName)
            .sorted()

/**
 * requireNoLiveCleanupThreads은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun requireNoLiveCleanupThreads() {
        if (liveCleanupThreadNames().isNotEmpty()) {
            throw HighContentionCleanupException(
                resourceKey = "cleanup-thread-leak",
                message = "cleanup thread zero-live gate failed",
            )
        }
    }

    @Synchronized
/**
 * awaitNoLiveCleanupThreads은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun awaitNoLiveCleanupThreads(timeout: Duration): Boolean {
        val timeoutNanos = timeout.positiveNanos("cleanup thread reap timeout")
        val startedAt = nanoTime()
        var interrupted = false
        try {
            cleanupThreads.toList().forEach { thread ->
                while (thread.isAlive) {
                    val remaining = timeoutNanos - elapsedSince(startedAt)
                    if (remaining <= 0L) {
                        return false
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedJoin(thread, remaining)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            return true
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

/**
 * closeResource은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun closeResource(resource: ResourceEntry): CleanupResult {
        val remainingCleanupNanos = (cleanupDeadlineNanos - nanoTime()).coerceAtLeast(0)
        val effectiveBudgetNanos = min(resource.cleanupBudgetNanos, remainingCleanupNanos)
        if (effectiveBudgetNanos <= 0L) {
            val failure = HighContentionCleanupException(
                resourceKey = resource.resourceKey,
                message = "cleanup deadline expired before ${resource.resourceKey}",
            )
            transition(resource, HighContentionResourceState.CLOSE_FAILED)
            return CleanupResult(failure, interrupted = false)
        }

        val task = FutureTask(resource.closeAction)
        val thread = Thread.ofPlatform()
            .daemon(true)
            .name("high-contention-cleanup-${resource.resourceKey}")
            .unstarted(task)
        cleanupThreads += thread
        thread.start()
        val startedAt = nanoTime()
        var interrupted = false

        while (thread.isAlive) {
            val remaining = effectiveBudgetNanos - elapsedSince(startedAt)
            if (remaining <= 0L) {
                thread.interrupt()
                val failure = HighContentionCleanupException(
                    resourceKey = resource.resourceKey,
                    message = "cleanup timed out for ${resource.resourceKey}",
                )
                transition(resource, HighContentionResourceState.CLOSE_FAILED)
                return CleanupResult(failure, interrupted)
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(thread, remaining)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }

        return try {
            task.get()
            transition(resource, HighContentionResourceState.CLOSED)
            CleanupResult(failure = null, interrupted = interrupted)
        } catch (error: Exception) {
            val cause = error.cause ?: error
            val failure = HighContentionCleanupException(
                resourceKey = resource.resourceKey,
                message = cause.message ?: "cleanup failed for ${resource.resourceKey}",
                cause = cause,
            )
            transition(resource, HighContentionResourceState.CLOSE_FAILED)
            CleanupResult(failure, interrupted)
        }
    }

/**
 * elapsedSince은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun elapsedSince(startedAt: Long): Long =
        nanoTime() - startedAt

/**
 * transition은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun transition(
        resource: ResourceEntry,
        state: HighContentionResourceState,
    ) {
        resource.state = state
        transitionObserver(HighContentionResourceTransition(resource.resourceKey, state))
    }

/**
 * ResourceEntry 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class ResourceEntry(
/**
 * id은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val id: Long,
/**
 * resourceKey은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val resourceKey: String,
/**
 * cleanupBudgetNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val cleanupBudgetNanos: Long,
/**
 * closeAction은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val closeAction: () -> Unit,
/**
 * state은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var state: HighContentionResourceState = HighContentionResourceState.ALLOCATED,
    )

/**
 * CleanupResult 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class CleanupResult(
/**
 * failure은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val failure: Throwable?,
/**
 * interrupted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val interrupted: Boolean,
    )
}

/**
 * positiveNanos은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun Duration.positiveNanos(name: String): Long {
    return toNanos().requirePositiveNumber(name)
}
