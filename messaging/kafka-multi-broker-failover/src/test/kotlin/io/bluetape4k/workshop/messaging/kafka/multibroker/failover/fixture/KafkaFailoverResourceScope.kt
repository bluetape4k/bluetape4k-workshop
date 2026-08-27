package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.producer.Producer

/**
 * scenario resource의 단일 owner입니다. close 순서는 collector → admin →
 * producer → consumer이며 이후 failure는 최초 failure에 suppressed로 보존합니다.
 */
class KafkaFailoverResourceScope : AutoCloseable {
    private enum class ResourceKind {
        COLLECTOR,
        ADMIN,
        PRODUCER,
        CONSUMER,
    }

    private data class Registration(
        val name: String,
        val kind: ResourceKind,
        val close: () -> Unit,
    )

    private val actions = linkedMapOf<String, Registration>()
    private var closed = false
    private var firstFailure: Throwable? = null
    private val order = mutableListOf<String>()

    val closeOrder: List<String>
        get() = order.toList()

    fun registerCollector(name: String = "collector", close: () -> Unit) =
        register(name, ResourceKind.COLLECTOR, close)

    fun registerCollector(collector: KafkaFailoverCollector, deadline: KafkaFailoverDeadline) =
        registerCollector { collector.stop(deadline) }

    fun registerAdmin(name: String = "admin", close: () -> Unit) =
        register(name, ResourceKind.ADMIN, close)

    fun registerAdmin(admin: Admin, name: String = "admin") = registerAdmin(name) { admin.close() }

    fun registerProducer(name: String = "producer", close: () -> Unit) =
        register(name, ResourceKind.PRODUCER, close)

    fun registerProducer(producer: Producer<*, *>, name: String = "producer") =
        registerProducer(name) { producer.close() }

    fun registerConsumer(name: String = "consumer", close: () -> Unit) =
        register(name, ResourceKind.CONSUMER, close)

    fun registerConsumer(consumer: Consumer<*, *>, name: String = "consumer") =
        registerConsumer(name) { consumer.close() }

    private fun register(name: String, kind: ResourceKind, close: () -> Unit) {
        check(!closed) { "resource scope is closed" }
        require(name.isNotBlank()) { "resource name must not be blank" }
        require(name !in actions) { "resource already registered: $name" }
        actions[name] = Registration(name, kind, close)
    }

    override fun close() {
        if (closed) return
        closed = true
        ResourceKind.entries
            .flatMap { kind -> actions.values.filter { it.kind == kind }.asReversed() }
            .forEach { registration ->
                order += registration.name
                runCatching { registration.close.invoke() }
                    .onFailure { error ->
                        if (firstFailure == null) firstFailure = error else firstFailure!!.addSuppressed(error)
                    }
            }
        firstFailure?.let { throw it }
    }
}
