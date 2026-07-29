package io.bluetape4k.workshop.flow.subject.bridge

import io.bluetape4k.coroutines.flow.extensions.subject.BehaviorSubject
import io.bluetape4k.coroutines.flow.extensions.subject.MulticastSubject
import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.coroutines.flow.extensions.subject.ReplaySubject
import io.bluetape4k.coroutines.flow.extensions.subject.UnicastWorkSubject
import io.bluetape4k.coroutines.flow.extensions.subject.awaitCollector
import io.bluetape4k.coroutines.flow.extensions.subject.awaitCollectors
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * callback-style device notification 을 명시적인 Subject semantic 을 가진 Flow stream 으로 연결합니다.
 *
 * 이 예제는 Subject mutation 을 bridge 내부에 가두고 caller 에게 read-only [Flow] view 만 노출합니다.
 */
class DeviceSubjectBridge(
    initialState: DeviceState,
    replayHistorySize: Int = 8,
    private val multicastSubscribers: Int = 2,
) {
    init {
        replayHistorySize.requirePositiveNumber("replayHistorySize")
        multicastSubscribers.requirePositiveNumber("multicastSubscribers")
    }

    private val eventSubject = PublishSubject<DeviceEvent>()
    private val stateSubject = BehaviorSubject(initialState)
    private val historySubject = ReplaySubject<DeviceEvent>(replayHistorySize)
    private val multicastSubject = MulticastSubject<DeviceEvent>(multicastSubscribers)
    private val workSubject = UnicastWorkSubject<WorkItem>()

    /** Event-only hot stream 입니다. 늦게 구독한 subscriber 는 과거 event 를 받지 않습니다. */
    val events: Flow<DeviceEvent> get() = eventSubject

    /** Latest-state hot stream 입니다. 늦게 구독한 subscriber 는 최신 state 를 먼저 받습니다. */
    val latestState: Flow<DeviceState> get() = stateSubject

    /** 늦게 구독한 subscriber 를 위한 replay 가능한 bounded event history 입니다. */
    val history: Flow<DeviceEvent> get() = historySubject

    /** 설정된 subscriber count 를 기다리는 coordinated multicast stream 입니다. */
    val multicastEvents: Flow<DeviceEvent> get() = multicastSubject

    /** single-consumer work queue 입니다. */
    val workItems: Flow<WorkItem> get() = workSubject

    /** 현재 latest state snapshot 입니다. */
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
