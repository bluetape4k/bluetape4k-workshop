package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.concurrent.get
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import kotlin.time.toKotlinDuration

data class FanoutResult(
    val deliveredSubscribers: Int,
    val evictedSubscribers: Int,
)

class BoundedJobEventFanout(
    private val writeTimeout: Duration,
    /** Adapter가 소유하고 lifecycle을 관리하는 executor입니다. */
    private val executor: ExecutorService = ForkJoinPool.commonPool(),
) {

    companion object: KLogging()

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
        val writes = snapshot.associateWith { (_, consumer) -> executor.submit { consumer(event) } }

        writes.forEach { (subscriber, future) ->
            val succeeded = runCatching { future.get(writeTimeout.toKotlinDuration()) }.isSuccess
            if (succeeded) {
                delivered++
                log.info { "Job is success. key=${subscriber.key}" }
            } else {
                future.cancel(true)
                subscribers.remove(subscriber.key, subscriber.value)
                log.warn { "Remove Job. key=${subscriber.key}" }
                evicted++
            }
        }
        return FanoutResult(delivered, evicted)
    }
}
