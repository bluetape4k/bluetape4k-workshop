package io.bluetape4k.workshop.flow.subject.bridge

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

class DeviceSubjectBridgeTest {

    private fun bridge(replayHistorySize: Int = 8): DeviceSubjectBridge = DeviceSubjectBridge(
        initialState = DeviceState("device-01", DeviceStatus.OFFLINE, "boot"),
        replayHistorySize = replayHistorySize,
        multicastSubscribers = 2,
    )

    @Test
    fun `publish subject delivers only events emitted after subscription`() = runSuspendTest {
        val bridge = bridge()
        val activeEvent = DeviceEvent("event-02", "device-01", DeviceEventType.TELEMETRY, "temperature=22")
        val received = mutableListOf<DeviceEvent>()

        bridge.publishEvent(DeviceEvent("event-01", "device-01", DeviceEventType.CONNECTED, "early"))

        val job = launch {
            bridge.events.take(1).toList(received)
        }
        bridge.awaitEventSubscribers()

        bridge.publishEvent(activeEvent)
        job.join()

        received shouldBeEqualTo listOf(activeEvent)
    }

    @Test
    fun `behavior subject sends latest state to late subscribers`() = runSuspendTest {
        val bridge = bridge()
        val latest = DeviceState("device-01", DeviceStatus.ONLINE, "event-10")

        bridge.updateState(DeviceState("device-01", DeviceStatus.DEGRADED, "event-09"))
        bridge.updateState(latest)

        bridge.currentState shouldBeEqualTo latest
        bridge.latestState.take(1).toList() shouldBeEqualTo listOf(latest)
    }

    @Test
    fun `replay subject keeps bounded event history for late subscribers`() = runSuspendTest {
        val bridge = bridge(replayHistorySize = 2)
        val first = DeviceEvent("event-01", "device-01", DeviceEventType.CONNECTED, "online")
        val second = DeviceEvent("event-02", "device-01", DeviceEventType.TELEMETRY, "battery=90")
        val third = DeviceEvent("event-03", "device-01", DeviceEventType.TELEMETRY, "battery=89")

        bridge.publishEvent(first)
        bridge.publishEvent(second)
        bridge.publishEvent(third)

        bridge.history.take(2).toList() shouldBeEqualTo listOf(second, third)
    }

    @Test
    fun `multicast subject waits for two subscribers and delivers the same event`() = runSuspendTest {
        val bridge = bridge()
        val event = DeviceEvent("event-20", "device-02", DeviceEventType.FAULT, "fan-stalled")
        val first = mutableListOf<DeviceEvent>()
        val second = mutableListOf<DeviceEvent>()

        val firstJob = launch { bridge.multicastEvents.take(1).toList(first) }
        val secondJob = launch { bridge.multicastEvents.take(1).toList(second) }
        bridge.awaitMulticastSubscribers()

        bridge.multicastEvent(event)
        firstJob.join()
        secondJob.join()

        first shouldBeEqualTo listOf(event)
        second shouldBeEqualTo listOf(event)
    }

    @Test
    fun `unicast work subject lets one consumer drain queued work`() = runSuspendTest {
        val bridge = bridge()
        val work = listOf(
            WorkItem("work-01", "device-01", "refresh-shadow"),
            WorkItem("work-02", "device-01", "persist-telemetry"),
            WorkItem("work-03", "device-02", "open-ticket"),
        )

        work.forEach { bridge.enqueueWork(it) }
        bridge.completeWorkQueue()

        val firstBatch = bridge.workItems.take(2).toList()
        val secondBatch = bridge.workItems.toList()

        firstBatch shouldBeEqualTo work.take(2)
        secondBatch shouldBeEqualTo work.drop(2)
    }

    @Test
    fun `unicast work subject rejects simultaneous collectors`() = runSuspendTest {
        val bridge = bridge()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            bridge.workItems.toList()
        }
        bridge.awaitWorkSubscriber()

        val failure = assertFailsWith<IllegalStateException> {
            bridge.workItems.take(1).toList()
        }
        failure.message.shouldNotBeNull()
        bridge.completeWorkQueue()
        first.await() shouldHaveSize 0
    }

    @Test
    fun `event stream propagates terminal errors to late collectors`() = runSuspendTest {
        val bridge = bridge()
        val boom = IllegalStateException("callback failed")

        bridge.failEvents(boom)

        val failure = assertFailsWith<IllegalStateException> {
            bridge.events.toList()
        }
        failure shouldBeEqualTo boom
    }

    @Test
    fun `work queue null error does not terminate the queue`() = runSuspendTest {
        val bridge = bridge()
        val work = WorkItem("work-null-01", "device-01", "continue-after-null-error")

        bridge.failWorkQueue(null)
        bridge.enqueueWork(work)

        bridge.workItems.take(1).toList() shouldBeEqualTo listOf(work)
    }

    @Test
    fun `work queue drains queued items before error termination`() = runSuspendTest {
        val bridge = bridge()
        val work = WorkItem("work-99", "device-99", "quarantine")
        val collector = async {
            bridge.workItems
                .catch { it shouldBeInstanceOf RuntimeException::class }
                .toList()
        }
        bridge.awaitWorkSubscriber()

        bridge.enqueueWork(work)
        bridge.failWorkQueue(RuntimeException("worker failed"))

        val result = collector.await()

        result shouldBeEqualTo listOf(work)
    }

    @Test
    fun `normal completion closes event subscribers`() = runSuspendTest {
        val bridge = bridge()
        val result = mutableListOf<DeviceEvent>()
        val job = launch {
            bridge.events.toList(result)
        }
        bridge.awaitEventSubscribers()
        yield()

        bridge.completeEvents()
        job.join()

        result shouldHaveSize 0
    }
}
