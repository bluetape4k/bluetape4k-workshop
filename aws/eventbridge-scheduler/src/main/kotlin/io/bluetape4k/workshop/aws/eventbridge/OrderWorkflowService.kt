package io.bluetape4k.workshop.aws.eventbridge

import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import tools.jackson.databind.ObjectMapper
import kotlin.coroutines.cancellation.CancellationException

/**
 * Coordinates the local EventBridge publish and Scheduler request flow.
 */
@Service
class OrderWorkflowService(
    private val properties: OrderWorkflowProperties,
    private val eventBridgePublisher: EventBridgePublisher,
    private val workflowScheduler: WorkflowScheduler,
    private val objectMapper: ObjectMapper,
) {

    /**
     * Starts an order workflow and reports each external boundary independently.
     */
    suspend fun startWorkflow(request: OrderWorkflowRequest): OrderWorkflowReport {
        validate(request)

        val detailJson = detailJson(request)
        val entry = eventBridgeEntry(request, detailJson)

        val eventBridgeStatus = try {
            eventBridgePublisher.publish(listOf(entry))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoundaryStatus.failed(e)
        }

        if (eventBridgeStatus.state == BoundaryState.FAILED) {
            return OrderWorkflowReport(
                eventBridge = eventBridgeStatus,
                scheduler = BoundaryStatus.skipped("EventBridge publish failed; scheduler request was not created."),
                idempotencyKey = request.idempotencyKey,
                correlationId = request.correlationId,
            )
        }

        val schedulerStatus = try {
            workflowScheduler.schedule(schedulerRequest(request, detailJson))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BoundaryStatus.failed(e)
        }

        return OrderWorkflowReport(
            eventBridge = eventBridgeStatus,
            scheduler = schedulerStatus,
            idempotencyKey = request.idempotencyKey,
            correlationId = request.correlationId,
        )
    }

    private fun validate(request: OrderWorkflowRequest) {
        request.orderId.requireNotBlank("orderId")
        request.customerId.requireNotBlank("customerId")
        request.idempotencyKey.requireNotBlank("idempotencyKey")
        request.correlationId.requireNotBlank("correlationId")
        if (request.reason.isNotEmpty()) {
            request.reason.requireNotBlank("reason")
        }
        properties.source.requireNotBlank("source")
        properties.detailType.requireNotBlank("detailType")
        properties.eventBusName.requireNotBlank("eventBusName")
        properties.schedulerGroupName.requireNotBlank("schedulerGroupName")
        properties.schedulerTargetArn.requireNotBlank("schedulerTargetArn")
        properties.flexibleTimeWindowMode.requireNotBlank("flexibleTimeWindowMode")
    }

    private fun eventBridgeEntry(
        request: OrderWorkflowRequest,
        detailJson: String,
    ): PutEventsRequestEntry =
        PutEventsRequestEntry.builder()
            .source(properties.source)
            .detailType(properties.detailType)
            .eventBusName(properties.eventBusName)
            .detail(detailJson)
            .time(request.scheduledAt)
            .traceHeader(request.correlationId)
            .build()

    private fun schedulerRequest(
        request: OrderWorkflowRequest,
        payloadJson: String,
    ): SchedulerWorkflowRequest =
        SchedulerWorkflowRequest(
            name = request.idempotencyKey,
            groupName = properties.schedulerGroupName,
            targetArn = properties.schedulerTargetArn,
            scheduleExpression = "at(${request.scheduledAt})",
            payloadJson = payloadJson,
            flexibleTimeWindowMode = properties.flexibleTimeWindowMode,
            idempotencyKey = request.idempotencyKey,
            correlationId = request.correlationId,
        )

    private fun detailJson(request: OrderWorkflowRequest): String =
        objectMapper.writeValueAsString(
            linkedMapOf(
                "orderId" to request.orderId,
                "customerId" to request.customerId,
                "workflow" to request.workflow.name,
                "scheduledAt" to request.scheduledAt.toString(),
                "idempotencyKey" to request.idempotencyKey,
                "correlationId" to request.correlationId,
                "reason" to request.reason.trim(),
            )
        )
}
