package io.bluetape4k.workshop.aws.eventbridge

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [EventBridgeSchedulerApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventBridgeSchedulerApplicationTest @Autowired constructor(
    private val service: OrderWorkflowService,
    private val properties: OrderWorkflowProperties,
    private val eventBridgePublisher: EventBridgePublisher,
    private val workflowScheduler: WorkflowScheduler,
) {

    @Test
    fun `application context wires local scheduler boundaries`() {
        service.shouldNotBeNull()
        properties.eventBusName shouldBeEqualTo "workshop-events"
        eventBridgePublisher shouldBeInstanceOf LocalEventBridgePublisher::class
        workflowScheduler shouldBeInstanceOf LocalWorkflowScheduler::class
    }
}
