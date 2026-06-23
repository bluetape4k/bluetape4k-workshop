package io.bluetape4k.workshop.flow.subject.bridge

import io.bluetape4k.coroutines.flow.extensions.subject.BehaviorSubject
import io.bluetape4k.coroutines.flow.extensions.subject.MulticastSubject
import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.coroutines.flow.extensions.subject.ReplaySubject
import io.bluetape4k.coroutines.flow.extensions.subject.UnicastWorkSubject
import io.bluetape4k.coroutines.flow.extensions.subject.awaitCollector
import io.bluetape4k.coroutines.flow.extensions.subject.awaitCollectors
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Bridges callback-style device notifications into Flow streams with explicit Subject semantics.
 *
 * This example keeps Subject mutation inside the bridge and exposes read-only [Flow] views to callers.
 */
class DeviceSubjectBridge(
    initialState: DeviceState,
    replayHistorySize: Int = 8,
    private val multicastSubscribers: Int = 2,
) {
    private val eventSubject = PublishSubject<DeviceEvent>()
    private val stateSubject = BehaviorSubject(initialState)
    private val historySubject = ReplaySubject<DeviceEvent>(replayHistorySize)
    private val multicastSubject = MulticastSubject<DeviceEvent>(multicastSubscribers)
    private val workSubject = UnicastWorkSubject<WorkItem>()

    /** Event-only hot stream: late subscribers do not receive past events. */
    val events: Flow<DeviceEvent> get() = eventSubject

    /** Latest-state hot stream: late subscribers receive the newest state first. */
    val latestState: Flow<DeviceState> get() = stateSubject

    /** Replayable bounded event history for late subscribers. */
    val history: Flow<DeviceEvent> get() = historySubject

    /** Coordinated multicast stream that waits for the configured subscriber count. */
    val multicastEvents: Flow<DeviceEvent> get() = multicastSubject

    /** Single-consumer work queue. */
    val workItems: Flow<WorkItem> get() = workSubject

    /** Current latest state snapshot. */
    val currentState: DeviceState get() = stateSubject.value

    suspend fun awaitEventSubscribers(minCollectorCount: Int = 1, timeout: Duration = 5.seconds) {
        eventSubject.awaitCollectors(minCollectorCount, timeout)
    }

    suspend fun awaitMulticastSubscribers(minCollectorCount: Int = multicastSubscribers, timeout: Duration = 5.seconds) {
        multicastSubject.awaitCollectors(minCollectorCount, timeout)
    }

    suspend fun awaitWorkSubscriber(timeout: Duration = 5.seconds) {
        workSubject.awaitCollector(timeout)
    }

    suspend fun publishEvent(event: DeviceEvent) {
        eventSubject.emit(event)
        historySubject.emit(event)
    }

    suspend fun updateState(state: DeviceState) {
        stateSubject.emit(state)
    }

    suspend fun multicastEvent(event: DeviceEvent) {
        multicastSubject.emit(event)
    }

    suspend fun enqueueWork(item: WorkItem) {
        workSubject.emit(item)
    }

    suspend fun completeEvents() {
        eventSubject.complete()
        historySubject.complete()
        multicastSubject.complete()
    }

    suspend fun completeState() {
        stateSubject.complete()
    }

    suspend fun completeWorkQueue() {
        workSubject.complete()
    }

    suspend fun failEvents(cause: Throwable?) {
        eventSubject.emitError(cause)
        historySubject.emitError(cause)
        multicastSubject.emitError(cause)
    }

    suspend fun failState(cause: Throwable?) {
        stateSubject.emitError(cause)
    }

    suspend fun failWorkQueue(cause: Throwable?) {
        workSubject.emitError(cause)
    }
}
