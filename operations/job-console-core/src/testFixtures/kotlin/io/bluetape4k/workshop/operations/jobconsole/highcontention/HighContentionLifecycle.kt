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
data class HighContentionDeadlines private constructor(
    val absoluteProfileDeadlineNanos: Long,
    val profileExecutionDeadlineNanos: Long,
    val cleanupDeadlineNanos: Long,
    private val runExecutionDeadlineNanos: Long,
    private val nanoTime: () -> Long,
) {

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
        fun start(
            profileDeadline: Duration,
            cleanupReserve: Duration,
            reportFinalizeReserve: Duration,
            cleanupActionBudgets: List<Duration>,
            runExecutionDeadlineNanos: Long,
            nanoTime: () -> Long,
        ): HighContentionDeadlines {
            val profileDeadlineNanos = profileDeadline.positiveNanos("profileDeadline")
            val cleanupReserveNanos = cleanupReserve.positiveNanos("cleanupReserve")
            val reportFinalizeReserveNanos = reportFinalizeReserve.positiveNanos("reportFinalizeReserve")
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
            val startedAt = nanoTime()
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

enum class HighContentionResourceState {
    ALLOCATED,
    STARTING,
    STARTED,
    CLOSED,
    CLOSE_FAILED,
}

data class HighContentionResourceTransition(
    val resourceKey: String,
    val state: HighContentionResourceState,
)

class HighContentionCleanupException(
    val resourceKey: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class HighContentionResource internal constructor(
    internal val id: Long,
)

class HighContentionLifecycle(
    private val cleanupDeadlineNanos: Long,
    private val nanoTime: () -> Long,
    private val transitionObserver: (HighContentionResourceTransition) -> Unit = {},
) : AutoCloseable {

    private val nextResourceId = AtomicLong()
    private val resources = mutableListOf<ResourceEntry>()
    private val failOpenBarriers = mutableListOf<() -> Unit>()
    private val cleanupThreads = mutableListOf<Thread>()
    private var closed = false

    @Synchronized
    fun allocate(
        resourceKey: String,
        cleanupBudget: Duration,
        closeAction: () -> Unit,
    ): HighContentionResource {
        check(!closed) { "lifecycle is closed" }
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
    fun start(
        resource: HighContentionResource,
        startAction: () -> Unit,
    ) {
        check(!closed) { "lifecycle is closed" }
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
    fun registerFailOpenBarrier(release: () -> Unit) {
        check(!closed) { "lifecycle is closed" }
        failOpenBarriers += release
    }

    override fun close() {
        finish()
    }

    @Synchronized
    fun finish(primaryFailure: Throwable? = null) {
        if (closed) {
            if (primaryFailure != null) {
                throw primaryFailure
            }
            return
        }
        closed = true
        val failures = mutableListOf<Throwable>()
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
        val first = failures.firstOrNull() ?: return
        failures.drop(1).forEach(first::addSuppressed)
        throw first
    }

    @Synchronized
    fun liveCleanupThreadNames(): List<String> =
        cleanupThreads
            .filter(Thread::isAlive)
            .map(Thread::getName)
            .sorted()

    fun requireNoLiveCleanupThreads() {
        if (liveCleanupThreadNames().isNotEmpty()) {
            throw HighContentionCleanupException(
                resourceKey = "cleanup-thread-leak",
                message = "cleanup thread zero-live gate failed",
            )
        }
    }

    @Synchronized
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

    private fun elapsedSince(startedAt: Long): Long =
        nanoTime() - startedAt

    private fun transition(
        resource: ResourceEntry,
        state: HighContentionResourceState,
    ) {
        resource.state = state
        transitionObserver(HighContentionResourceTransition(resource.resourceKey, state))
    }

    private data class ResourceEntry(
        val id: Long,
        val resourceKey: String,
        val cleanupBudgetNanos: Long,
        val closeAction: () -> Unit,
        var state: HighContentionResourceState = HighContentionResourceState.ALLOCATED,
    )

    private data class CleanupResult(
        val failure: Throwable?,
        val interrupted: Boolean,
    )
}

private fun Duration.positiveNanos(name: String): Long {
    return toNanos().requirePositiveNumber(name)
}
