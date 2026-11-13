package io.bluetape4k.workshop.flow.event.aggregation

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.coroutines.flow.exceptions.FlowOperationException
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.coroutines.support.log
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.coroutines.runSuspendTest
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class OrderEventAggregationPipelineTest {

    companion object: KLogging()

    private val pipeline = OrderEventAggregationPipeline()

    @Test
    fun `chunked activity emits bounded event summaries`() = runSuspendTest {
        val summaries = pipeline
            .chunkedActivity(sampleEvents().asFlow(), chunkSize = 2)
            .log("Chunk")
            .toList()

        summaries.map { it.eventCount } shouldBeEqualTo listOf(2, 2, 1)
        summaries.map { it.orderIds } shouldBeEqualTo listOf(
            setOf("order-1"),
            setOf("order-1", "order-2"),
            setOf("order-2"),
        )
        summaries.last().latestStatuses["order-2"] shouldBeEqualTo OrderStatus.SHIPPED
    }

    @Test
    fun `count or time activity emits count boundaries and completion partial batch`() = runSuspendTest {
        val summaries = pipeline.countOrTimeActivity(
            events = sampleEvents().asFlow(),
            maxSize = 2,
            timeout = 1.seconds,
        )
            .log("Summary")
            .toList()

        summaries.map { it.eventCount } shouldBeEqualTo listOf(2, 2, 1)
    }

    @Test
    fun `count or time activity emits a partial batch after an idle timeout`() = runSuspendTest {
        val events = flow {
            emit(sampleEvents()[0])
            delay(10.milliseconds)
            emit(sampleEvents()[1])
            delay(60.milliseconds)
            emit(sampleEvents()[2])
        }

        val summaries = pipeline.countOrTimeActivity(events, maxSize = 3, timeout = 50.milliseconds)
            .log("Summary")
            .toList()

        summaries.map { it.eventCount } shouldBeEqualTo listOf(2, 1)
    }

    @Test
    fun `count or time activity locks the upstream idle timeout observation`() = runSuspendTest {
        val events = flow {
            emit(sampleEvents()[0])
            delay(40.milliseconds)
            emit(sampleEvents()[1])
            delay(20.milliseconds)
            emit(sampleEvents()[2])
        }

        val summaries = pipeline.countOrTimeActivity(events, maxSize = 3, timeout = 50.milliseconds)
            .log("Summary")
            .toList()

        // bluetape4k-coroutines 1.12.1 re-registers the timeout after each receive.
        summaries.map { it.eventCount } shouldBeEqualTo listOf(3)
    }

    @Test
    fun `rolling activity emits overlapping summary windows`() = runSuspendTest {
        val summaries = pipeline.rollingActivity(sampleEvents().asFlow(), size = 3, step = 2)
            .log("Summary")
            .toList()

        summaries.map { it.eventCount } shouldBeEqualTo listOf(3, 3, 1)
        summaries.first().latestStatuses["order-1"] shouldBeEqualTo OrderStatus.PAID
        summaries.last().latestStatuses["order-2"] shouldBeEqualTo OrderStatus.SHIPPED
    }

    @Test
    fun `rolling activity emits full and partial tail windows`() = runSuspendTest {
        val summaries = pipeline.rollingActivity(sampleEvents().take(4).asFlow(), size = 3, step = 1)
            .log("Summary")
            .toList()

        summaries.map { it.eventCount } shouldBeEqualTo listOf(3, 3, 2, 1)
    }

    @Test
    fun `grouped events partition completed stream by order id`() = runSuspendTest {
        val groups = pipeline.groupedByOrder(sampleEvents().asFlow())
            .log("GroupedByOrder")
            .toList()
            .associateBy { it.key }

        groups.keys shouldBeEqualTo setOf("order-1", "order-2")
        groups.getValue("order-1").values.map { it.eventType } shouldBeEqualTo
                listOf("OrderCreated", "LineAdded", "PaymentAuthorized")
        groups.getValue("order-2").values.map { it.eventType } shouldBeEqualTo
                listOf("OrderCreated", "ShipmentStarted")
    }

    @Test
    fun `finite high cardinality grouping emits every order group once`() = runSuspendTest {
        val orderSize = 160
        val events = List(orderSize) { index ->
            OrderCreated("order-$index", "customer-$index", t(index))
        }

        val groups = withTimeout(5.seconds) {
            pipeline.groupedByOrder(events.asFlow()).log("GroupedByOrder").toList()
        }

        groups shouldHaveSize orderSize
        groups.map { it.key }.toSet() shouldBeEqualTo List(orderSize) { "order-$it" }.toSet()
        groups.all { it.values.single().orderId == it.key }.shouldBeTrue()
    }

    @Test
    fun `read models accumulate state per order id`() = runSuspendTest {
        val models = pipeline.readModels(sampleEvents().asFlow())
            .log("ReadModel")
            .toList()

        models.first().orders.shouldBeEmpty()
        val final = models.last()
        final.orders.getValue("order-1").status shouldBeEqualTo OrderStatus.PAID
        final.orders.getValue("order-1").lineCount shouldBeEqualTo 1
        final.orders.getValue("order-1").itemQuantity shouldBeEqualTo 2
        final.orders.getValue("order-2").status shouldBeEqualTo OrderStatus.SHIPPED
        final.orders.getValue("order-2").version shouldBeEqualTo 2
    }

    @Test
    fun `bounded read model growth remains predictable for many active orders`() = runSuspendTest {
        val orderSize = 120
        val events = List(orderSize) { index ->
            OrderCreated("order-$index", "customer-$index", t(index))
        }

        val models = pipeline.readModels(events.asFlow())
            .log("Models")
            .toList()

        models shouldHaveSize orderSize + 1
        models.last().orders shouldHaveSize orderSize
    }

    @Test
    fun `unchanged status runs collapse repeated created updates`() = runSuspendTest {
        val runs = pipeline.statusRuns(
            flowOf(
                OrderCreated("order-1", "customer-1", t(1)),
                LineAdded("order-1", "sku-1", 1, t(2)),
                LineAdded("order-1", "sku-2", 2, t(3)),
                PaymentAuthorized("order-1", 1000, t(4)),
            ),
            orderId = "order-1",
        )
            .log("StatusRun")
            .toList()

        runs.map { it.status } shouldBeEqualTo listOf(OrderStatus.CREATED, OrderStatus.PAID)
        runs.first().stateCount shouldBeEqualTo 3
        runs.first().finalState.itemQuantity shouldBeEqualTo 3
    }

    @Test
    fun `long unchanged status run collapses but retains run until boundary`() = runSuspendTest {
        val events = listOf(OrderCreated("order-1", "customer-1", t(1))) +
                List(20) { index -> LineAdded("order-1", "sku-$index", 1, t(index + 1)) } +
                PaymentAuthorized("order-1", 1000, t(30))

        val runs = pipeline.statusRuns(events.asFlow(), "order-1")
            .log("StatusRun")
            .toList()

        runs.map { it.status } shouldBeEqualTo listOf(OrderStatus.CREATED, OrderStatus.PAID)
        runs.first().stateCount shouldBeEqualTo 21
    }

    @Test
    fun `transitions emit lifecycle changes only`() = runSuspendTest {
        val transitions = pipeline.transitions(sampleEvents().asFlow(), "order-1")
            .log("Transition")
            .toList()

        transitions.map { it.previousStatus to it.currentStatus } shouldBeEqualTo
                listOf(OrderStatus.CREATED to OrderStatus.PAID)
    }

    @Test
    fun `audit stream preserves readable event order`() = runSuspendTest {
        val audit = pipeline.audit(sampleEvents().asFlow())
            .log("Audit")
            .toList()

        audit.map { it.sequence } shouldBeEqualTo listOf(1, 2, 3, 4, 5)
        audit.map { it.eventType } shouldBeEqualTo listOf(
            "OrderCreated",
            "LineAdded",
            "PaymentAuthorized",
            "OrderCreated",
            "ShipmentStarted"
        )
        audit.first().status shouldBeEqualTo OrderStatus.CREATED
    }

    @Test
    fun `domain values reject blank ids and non positive amounts`() = runSuspendTest {
        assertFailsWith<IllegalArgumentException> { OrderCreated(" ", "customer-1", t(1)) }
        assertFailsWith<IllegalArgumentException> { LineAdded("order-1", "sku-1", 0, t(1)) }
        assertFailsWith<IllegalArgumentException> { PaymentAuthorized("order-1", 0, t(1)) }
        assertFailsWith<IllegalArgumentException> {
            OrderAuditEntry.from(0, OrderCreated("order-1", "customer-1", t(1)))
        }
    }

    @Test
    fun `domain values reject control characters and overlong identifiers`() = runSuspendTest {
        assertFailsWith<IllegalArgumentException> { OrderCreated("order\n1", "customer-1", t(1)) }
        assertFailsWith<IllegalArgumentException> { LineAdded("order-1", "sku-${"x".repeat(80)}", 1, t(1)) }
        assertFailsWith<IllegalArgumentException> { ShipmentStarted("order-1", "carrier\t1", "track-1", t(1)) }
    }

    @Test
    fun `sensitive fields are trimmed bounded and reject control characters`() = runSuspendTest {
        OrderCreated(" order-1 ", " customer-1 ", t(1)).customerId shouldBeEqualTo "customer-1"
        ShipmentStarted("order-1", " carrier-1 ", " track-1 ", t(1)).trackingNumber shouldBeEqualTo "track-1"
        OrderCancelled("order-1", " customer changed mind ", t(1)).reason shouldBeEqualTo "customer changed mind"

        assertFailsWith<IllegalArgumentException> { OrderCreated("order-1", "customer\n1", t(1)) }
        assertFailsWith<IllegalArgumentException> { ShipmentStarted("order-1", "carrier-1", "x".repeat(129), t(1)) }
        assertFailsWith<IllegalArgumentException> { OrderCancelled("order-1", "bad\treason", t(1)) }
    }

    @Test
    fun `event construction has no public copy bypass for validation`() = runSuspendTest {
        OrderCreated::class.java.methods.any { it.name == "copy" }.shouldBeFalse()
        LineAdded::class.java.methods.any { it.name == "copy" }.shouldBeFalse()
        PaymentAuthorized::class.java.methods.any { it.name == "copy" }.shouldBeFalse()
        ShipmentStarted::class.java.methods.any { it.name == "copy" }.shouldBeFalse()
        OrderCancelled::class.java.methods.any { it.name == "copy" }.shouldBeFalse()
    }

    @Test
    fun `debug rendering hides customer tracking and cancellation details`() = runSuspendTest {
        val rendered = listOf(
            OrderCreated("order-1", "secret-customer", t(1)).toString(),
            ShipmentStarted("order-1", "carrier-1", "secret-tracking", t(2)).toString(),
            OrderCancelled("order-1", "secret-reason", t(3)).toString(),
        ).joinToString()

        val audit = pipeline.audit(
            flowOf(
                OrderCreated("order-1", "secret-customer", t(1)),
                ShipmentStarted("order-1", "carrier-1", "secret-tracking", t(2)),
                OrderCancelled("order-1", "secret-reason", t(3)),
            ),
        ).log("Audit").toList().joinToString()

        log.debug { "rendered=$rendered" }
        rendered shouldNotContain "secret-customer"
        rendered shouldNotContain "secret-tracking"
        rendered shouldNotContain "secret-reason"

        log.debug { "audit=$audit" }
        audit shouldNotContain "secret-customer"
        audit shouldNotContain "secret-tracking"
        audit shouldNotContain "secret-reason"
    }

    @Test
    fun `invalid pipeline parameters fail before collection`() = runSuspendTest {
        assertFailsWith<IllegalArgumentException> {
            pipeline.chunkedActivity(flowOf(), 0).toList()
        }
        assertFailsWith<IllegalArgumentException> {
            pipeline.countOrTimeActivity(flowOf(), 0, 1.seconds).toList()
        }
        assertFailsWith<IllegalArgumentException> {
            pipeline.countOrTimeActivity(flowOf(), 1, Duration.ZERO).toList()
        }
        assertFailsWith<IllegalArgumentException> {
            pipeline.countOrTimeActivity(flowOf(), 1, Duration.INFINITE).toList()
        }
        assertFailsWith<IllegalArgumentException> {
            pipeline.rollingActivity(flowOf(), size = 0, step = 1).toList()
        }
        assertFailsWith<IllegalArgumentException> {
            pipeline.rollingActivity(flowOf(), size = 2, step = 0).toList()
        }
        assertFailsWith<IllegalArgumentException> {
            pipeline.rollingActivity(flowOf(), size = 2, step = 3).toList()
        }
    }

    @Test
    fun `cancelled status stays terminal while audit version advances`() = runSuspendTest {
        val states = pipeline.readModels(
            flowOf(
                OrderCreated("order-1", "customer-1", t(1)),
                OrderCancelled("order-1", "bad address", t(2)),
                LineAdded("order-1", "sku-1", 1, t(3)),
            ),
        ).log("Models").toList().mapNotNull { it.orders["order-1"] }

        states.map { it.status } shouldBeEqualTo listOf(
            OrderStatus.CREATED,
            OrderStatus.CANCELLED,
            OrderStatus.CANCELLED
        )
        states.last().version shouldBeEqualTo 3
    }

    @Test
    fun `duplicate and out of order lifecycle events converge deterministically`() = runSuspendTest {
        val final = pipeline.readModels(
            flowOf(
                ShipmentStarted("order-1", "carrier-1", "track-1", t(1)),
                PaymentAuthorized("order-1", 1000, t(2)),
                PaymentAuthorized("order-1", 1200, t(3)),
                OrderCancelled("order-1", "customer request", t(4)),
            ),
        ).log("Models").toList().last().orders.getValue("order-1")

        final.status shouldBeEqualTo OrderStatus.CANCELLED
        final.authorizedAmountCents shouldBeEqualTo 1200
        final.version shouldBeEqualTo 4
    }

    @Test
    fun `collector cancellation stops upstream collection`() = runSuspendTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val source = flow {
            try {
                emit(OrderCreated("order-1", "customer-1", t(1)))
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }

        val job = async {
            pipeline.readModels(source).log("Models").toList()
        }.log("Job")

        started.await()
        job.cancelAndJoin()

        cancelled.get().shouldBeTrue()
    }

    @Test
    fun `upstream failure propagates through each aggregation path`() = runSuspendTest {
        val boom = IllegalStateException("upstream failed")
        val emitted = mutableListOf<OrderActivitySummary>()

        val countOrTimeFailure = assertFailsWith<IllegalStateException> {
            pipeline.countOrTimeActivity(
                failingEvents(boom),
                maxSize = 3,
                timeout = 1.seconds,
            ).collect { emitted += it }
        }

        countOrTimeFailure.message shouldBeEqualTo boom.message
        emitted.shouldBeEmpty()

        assertFailsWith<IllegalStateException> { pipeline.chunkedActivity(failingEvents(boom), 2).toList() }
        assertFailsWith<IllegalStateException> { pipeline.rollingActivity(failingEvents(boom), 2, 1).toList() }
        assertFailsWith<IllegalStateException> { pipeline.readModels(failingEvents(boom)).toList() }
        assertFailsWith<IllegalStateException> { pipeline.statusRuns(failingEvents(boom), "order-1").toList() }
        assertFailsWith<IllegalStateException> { pipeline.transitions(failingEvents(boom), "order-1").toList() }
        assertFailsWith<IllegalStateException> { pipeline.audit(failingEvents(boom)).toList() }

        val grouped = assertFailsWith<FlowOperationException> {
            pipeline.groupedByOrder(failingEvents(boom)).log("Grouped").toList()
        }
        val groupByFailure = grouped.cause.shouldBeInstanceOf<FlowOperationException>()
        groupByFailure.cause shouldBeEqualTo boom
    }

    @Test
    fun `cancellation exception is not wrapped by aggregation paths`() = runSuspendTest {
        val cancellation = CancellationException("collector stopped")

        assertFailsWith<CancellationException> {
            pipeline.countOrTimeActivity(cancelledEvents(cancellation), 2, 1.seconds).toList()
        }

        assertFailsWith<CancellationException> { pipeline.chunkedActivity(cancelledEvents(cancellation), 2).toList() }
        assertFailsWith<CancellationException> { pipeline.groupedByOrder(cancelledEvents(cancellation)).toList() }
        assertFailsWith<CancellationException> { pipeline.readModels(cancelledEvents(cancellation)).toList() }
    }

    private fun sampleEvents(): List<OrderEvent> = listOf(
        OrderCreated("order-1", "customer-1", t(1)),
        LineAdded("order-1", "sku-1", 2, t(2)),
        PaymentAuthorized("order-1", 2500, t(3)),
        OrderCreated("order-2", "customer-2", t(4)),
        ShipmentStarted("order-2", "carrier-1", "track-1", t(5)),
    )

    private fun List<OrderEvent>.asFlow() = flowOf(*toTypedArray())

    private fun failingEvents(boom: RuntimeException) = flow {
        emit(OrderCreated("order-1", "customer-1", t(1)))
        throw boom
    }

    private fun cancelledEvents(cancellation: CancellationException) = flow<OrderEvent> {
        emit(OrderCreated("order-1", "customer-1", t(1)))
        throw cancellation
    }

    private fun t(second: Int): Instant =
        Instant.parse("2026-06-29T00:00:00Z") + second.seconds()
}
