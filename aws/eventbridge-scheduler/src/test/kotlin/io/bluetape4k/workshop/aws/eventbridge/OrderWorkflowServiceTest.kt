package io.bluetape4k.workshop.aws.eventbridge

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderWorkflowServiceTest {

    @Test
    fun `maps order workflow to EventBridge entry and Scheduler request`() = runSuspendIO {
        val fixture = serviceFixture()
        val request = sampleRequest()

        val report = fixture.service.startWorkflow(request)

        report.eventBridge.state shouldBeEqualTo BoundaryState.PUBLISHED
        report.scheduler.state shouldBeEqualTo BoundaryState.PUBLISHED
        report.idempotencyKey shouldBeEqualTo "order-100-payment-reminder"
        report.correlationId shouldBeEqualTo "corr-100"

        val entry = fixture.eventBridge.entries.single()
        entry.source() shouldBeEqualTo "bluetape4k.workshop.orders"
        entry.detailType() shouldBeEqualTo "OrderWorkflowRequested"
        entry.eventBusName() shouldBeEqualTo "workshop-events"
        entry.traceHeader() shouldBeEqualTo "corr-100"
        entry.detail() shouldContain "\"orderId\":\"order-100\""
        entry.detail() shouldContain "\"idempotencyKey\":\"order-100-payment-reminder\""
        entry.detail() shouldContain "\"correlationId\":\"corr-100\""

        val schedule = fixture.scheduler.requests.single()
        schedule.name shouldBeEqualTo "order-100-payment-reminder"
        schedule.groupName shouldBeEqualTo "order-workflows"
        schedule.targetArn shouldBeEqualTo "arn:aws:lambda:ap-northeast-2:123456789012:function:order-reminder"
        schedule.scheduleExpression shouldBeEqualTo "at(2026-07-02T09:30:00Z)"
        schedule.flexibleTimeWindowMode shouldBeEqualTo "OFF"
        schedule.idempotencyKey shouldBeEqualTo "order-100-payment-reminder"
        schedule.correlationId shouldBeEqualTo "corr-100"
        schedule.payloadJson shouldContain "\"workflow\":\"PAYMENT_REMINDER\""
    }

    @Test
    fun `EventBridge failure skips Scheduler request`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.eventBridge.failure = IllegalStateException("event bus denied")

        val report = fixture.service.startWorkflow(sampleRequest())

        report.eventBridge.state shouldBeEqualTo BoundaryState.FAILED
        report.eventBridge.message shouldContain "event bus denied"
        report.scheduler.state shouldBeEqualTo BoundaryState.SKIPPED
        fixture.scheduler.requests.size shouldBeEqualTo 0
    }

    @Test
    fun `Scheduler failure keeps published EventBridge status`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.scheduler.failure = IllegalStateException("schedule denied")

        val report = fixture.service.startWorkflow(sampleRequest())

        report.eventBridge.state shouldBeEqualTo BoundaryState.PUBLISHED
        report.scheduler.state shouldBeEqualTo BoundaryState.FAILED
        report.scheduler.message shouldContain "schedule denied"
        fixture.eventBridge.entries.size shouldBeEqualTo 1
    }

    @Test
    fun `rethrows cancellation from EventBridge boundary`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.eventBridge.failure = CancellationException("publish cancelled")

        assertFailsWith<CancellationException> {
            fixture.service.startWorkflow(sampleRequest())
        }
    }

    @Test
    fun `rejects blank required request fields`() = runSuspendIO {
        val fixture = serviceFixture()

        assertFailsWith<IllegalArgumentException> {
            fixture.service.startWorkflow(sampleRequest().copy(orderId = " "))
        }
    }

    private fun serviceFixture(): ServiceFixture {
        val eventBridge = CapturingEventBridgePublisher()
        val scheduler = CapturingWorkflowScheduler()
        val properties = OrderWorkflowProperties(
            source = "bluetape4k.workshop.orders",
            detailType = "OrderWorkflowRequested",
            eventBusName = "workshop-events",
            schedulerGroupName = "order-workflows",
            schedulerTargetArn = "arn:aws:lambda:ap-northeast-2:123456789012:function:order-reminder",
        )

        return ServiceFixture(
            service = OrderWorkflowService(
                properties = properties,
                eventBridgePublisher = eventBridge,
                workflowScheduler = scheduler,
                objectMapper = jacksonObjectMapper(),
            ),
            eventBridge = eventBridge,
            scheduler = scheduler,
        )
    }

    private fun sampleRequest(): OrderWorkflowRequest =
        OrderWorkflowRequest(
            orderId = "order-100",
            customerId = "customer-200",
            workflow = OrderWorkflow.PAYMENT_REMINDER,
            scheduledAt = Instant.parse("2026-07-02T09:30:00Z"),
            idempotencyKey = "order-100-payment-reminder",
            correlationId = "corr-100",
            reason = "Payment still pending",
        )

    private class ServiceFixture(
        val service: OrderWorkflowService,
        val eventBridge: CapturingEventBridgePublisher,
        val scheduler: CapturingWorkflowScheduler,
    )

    private class CapturingEventBridgePublisher: EventBridgePublisher {
        val entries = mutableListOf<PutEventsRequestEntry>()
        var failure: Throwable? = null

        override suspend fun publish(entries: List<PutEventsRequestEntry>): BoundaryStatus {
            failure?.let { throw it }
            this.entries += entries
            return BoundaryStatus.published("published locally")
        }
    }

    private class CapturingWorkflowScheduler: WorkflowScheduler {
        val requests = mutableListOf<SchedulerWorkflowRequest>()
        var failure: Throwable? = null

        override suspend fun schedule(request: SchedulerWorkflowRequest): BoundaryStatus {
            failure?.let { throw it }
            requests += request
            return BoundaryStatus.published("scheduled locally")
        }
    }
}
