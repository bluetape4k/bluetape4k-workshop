package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class FanoutResult(
    val deliveredSubscribers: Int,
    val evictedSubscribers: Int,
)

class BoundedJobEventFanout(
    private val writeTimeout: Duration,
) {
    private val subscribers = ConcurrentHashMap<String, (JobEvent) -> Unit>()

    val subscriberCount: Int
        get() = subscribers.size

    fun subscribe(id: String, consumer: (JobEvent) -> Unit): AutoCloseable {
        subscribers[id] = consumer
        return AutoCloseable { subscribers.remove(id, consumer) }
    }

    fun publish(event: JobEvent): FanoutResult {
        val snapshot = subscribers.entries.toList()
        var delivered = 0
        var evicted = 0
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val writes = snapshot.associateWith { (_, consumer) -> executor.submit { consumer(event) } }
            writes.forEach { (subscriber, future) ->
                val succeeded = runCatching { future.get(writeTimeout.toMillis(), TimeUnit.MILLISECONDS) }.isSuccess
                if (succeeded) {
                    delivered++
                } else {
                    future.cancel(true)
                    subscribers.remove(subscriber.key, subscriber.value)
                    evicted++
                }
            }
        }
        return FanoutResult(delivered, evicted)
    }
}
