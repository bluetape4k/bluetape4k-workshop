package io.bluetape4k.workshop.aws.eventbridge

import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/**
 * 로컬 EventBridge 발행과 Scheduler 요청 흐름을 조율합니다.
 */
@Service
class OrderWorkflowService(
    private val properties: OrderWorkflowProperties,
    private val eventBridgePublisher: EventBridgePublisher,
    private val workflowScheduler: WorkflowScheduler,
    private val objectMapper: ObjectMapper,
) {

    /**
     * 주문 처리 흐름을 시작하고 각 외부 경계를 독립적으로 보고합니다.
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
            scheduleExpression = "at(${SCHEDULER_AT_FORMATTER.format(request.scheduledAt)})",
            scheduleExpressionTimezone = "UTC",
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

    companion object {
        private val SCHEDULER_AT_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(ZoneOffset.UTC)
    }
}
