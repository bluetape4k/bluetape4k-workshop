package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRead
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import java.time.Duration
import java.time.Instant

internal const val MAX_FOREGROUND_REPLAY_EVENTS = 10_000
private const val MAX_FOREGROUND_REPLAY_SECONDS = 2L
private val MAX_FOREGROUND_REPLAY_DURATION: Duration = Duration.ofSeconds(MAX_FOREGROUND_REPLAY_SECONDS)

internal class RehydrationLimitExceeded(message: String) : IllegalStateException(message)

internal class RehydrationRequest<T : Any>(
    val stream: StreamKey,
    val emptyState: () -> T,
    val restoreSnapshot: (String) -> T,
    val apply: (T, List<EventEnvelope>) -> T,
    val keyVersionAvailable: (Int) -> Boolean,
)

internal class RehydratedState<T : Any>(
    val state: T,
    val snapshotVersion: Long,
    val replayedEvents: Int,
)

/** Snapshots are optional accelerators; invalid, stale, or retired-key snapshots fall back to events. */
internal class BoundedRehydrator(
    private val eventStore: EventStorePort,
    private val snapshotLoader: (StreamKey) -> EventSnapshot?,
    private val now: () -> Instant = Instant::now,
    private val maxEvents: Int = MAX_FOREGROUND_REPLAY_EVENTS,
    private val maxDuration: Duration = MAX_FOREGROUND_REPLAY_DURATION,
) {
    fun <T : Any> rehydrate(request: RehydrationRequest<T>): RehydratedState<T> =
        rehydrateFrom(request, loadSnapshot(request), now())

    private fun <T : Any> rehydrateFrom(
        request: RehydrationRequest<T>,
        snapshot: EventSnapshot?,
        startedAt: Instant,
    ): RehydratedState<T> {
        var state = snapshot?.let { request.restoreSnapshot(it.canonicalState) } ?: request.emptyState()
        var afterVersion = snapshot?.metadata?.streamVersion ?: 0L
        val snapshotVersion = afterVersion
        var replayed = 0
        while (true) {
            requireWithinBudget(startedAt, replayed)
            val page = eventStore.load(EventStoreRead(request.stream, afterVersion))
            if (page.committedHead < afterVersion) return rehydrateFrom(request, null, startedAt)
            if (page.events.isEmpty()) return RehydratedState(state, snapshotVersion, replayed)
            replayed += page.events.size
            requireWithinBudget(startedAt, replayed)
            state = request.apply(state, page.events)
            afterVersion = page.events.last().stream.version
        }
    }

    private fun <T : Any> loadSnapshot(request: RehydrationRequest<T>): EventSnapshot? =
        try {
            snapshotLoader(request.stream)?.takeIf { request.keyVersionAvailable(it.metadata.keyVersion) }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun requireWithinBudget(startedAt: Instant, replayed: Int) {
        if (replayed > maxEvents) throw RehydrationLimitExceeded("event replay exceeds $maxEvents events")
        if (Duration.between(startedAt, now()) > maxDuration) {
            throw RehydrationLimitExceeded("event replay exceeds $maxDuration")
        }
    }
}
