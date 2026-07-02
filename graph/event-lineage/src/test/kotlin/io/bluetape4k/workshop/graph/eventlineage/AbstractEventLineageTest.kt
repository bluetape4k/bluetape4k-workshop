package io.bluetape4k.workshop.graph.eventlineage

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.workshop.graph.eventlineage.schema.ActorLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.AggregateLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.ApprovedByLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.CausedByLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.DecidedByLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.DecisionLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.EmitsLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.EventLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.SupersedesLabel
import io.bluetape4k.workshop.graph.eventlineage.seed.EventLineageSeed
import io.bluetape4k.workshop.graph.eventlineage.seed.seedEventLineage
import io.bluetape4k.workshop.graph.eventlineage.service.EventLineageService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Abstract test suite for [EventLineageService].
 *
 * The seed graph explains an order approval state through aggregate, event,
 * actor, and decision vertices. Concrete subclasses provide the graph backend.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractEventLineageTest {

    protected abstract val graphName: String
    protected abstract val ops: GraphOperations
    protected abstract val service: EventLineageService

    protected lateinit var seed: EventLineageSeed

    @BeforeEach
    fun cleanGraph() {
        ops.dropGraph(graphName)
        service.initialize()
        seed = seedEventLineage(service)
    }

    @Test
    fun `seed creates event aggregate actor decision vertices and expected lineage edges`() {
        seed.orderAggregate.label shouldBeEqualTo AggregateLabel.label
        seed.orderApproved.label shouldBeEqualTo EventLabel.label
        seed.managerApproval.label shouldBeEqualTo DecisionLabel.label
        seed.managerActor.label shouldBeEqualTo ActorLabel.label

        service.edgeCount(EmitsLabel.label) shouldBeEqualTo 7L
        service.edgeCount(CausedByLabel.label) shouldBeEqualTo 4L
        service.edgeCount(ApprovedByLabel.label) shouldBeEqualTo 1L
        service.edgeCount(DecidedByLabel.label) shouldBeEqualTo 1L
        service.edgeCount(SupersedesLabel.label) shouldBeEqualTo 1L
    }

    @Test
    fun `eventsForAggregate returns aggregate events in deterministic audit order`() {
        val eventIds = service.eventsForAggregate("order-1001").map { it.nodeId }

        eventIds shouldBeEqualTo listOf(
            "order-created",
            "risk-scored",
            "discount-requested",
            "manager-approved",
            "order-approved",
            "order-approval-corrected",
            "manual-note-added",
        )
    }

    @Test
    fun `causalPath returns path from approval event to root order creation`() {
        val path = service.causalPath(
            eventId = "order-approved",
            rootEventId = "order-created",
            maxDepth = 5,
        )

        path.nodes.map { it.nodeId } shouldBeEqualTo listOf(
            "order-approved",
            "manager-approved",
            "discount-requested",
            "risk-scored",
            "order-created",
        )
        path.edgeLabels shouldBeEqualTo listOf(
            CausedByLabel.label,
            CausedByLabel.label,
            CausedByLabel.label,
            CausedByLabel.label,
        )
    }

    @Test
    fun `auditTrailForAggregate reconstructs events approvals and deciding actor`() {
        val trail = service.auditTrailForAggregate("order-1001")

        trail.aggregate.nodeId shouldBeEqualTo "order-1001"
        trail.aggregate.properties["state"] shouldBeEqualTo "APPROVED"
        trail.events.map { it.nodeId } shouldContain "order-approved"
        trail.approvals shouldHaveSize 1
        trail.approvals.first().event.nodeId shouldBeEqualTo "order-approved"
        trail.approvals.first().decision.nodeId shouldBeEqualTo "decision-discount-approval"
        trail.approvals.first().actor.nodeId shouldBeEqualTo "manager-maria"
        trail.rootCauses.map { it.nodeId } shouldContain "order-created"
    }

    @Test
    fun `supersededChain follows newest event to previous correction target`() {
        val chain = service.supersededChain("order-approval-corrected")

        chain.map { it.nodeId } shouldBeEqualTo listOf("order-approval-corrected", "order-approved")
    }

    @Test
    fun `missingCausalLinks reports emitted events without root cause or upstream event`() {
        val missing = service.missingCausalLinks("order-1001")

        missing.map { it.nodeId } shouldBeEqualTo listOf("manual-note-added")
    }

    @Test
    fun `unknown aggregate and event ids return empty query results`() {
        service.eventsForAggregate("missing-order").shouldBeEmpty()
        service.auditTrailForAggregate("missing-order").events.shouldBeEmpty()
        service.causalPath("missing-event", "order-created").nodes.shouldBeEmpty()
        service.supersededChain("missing-event").shouldBeEmpty()
        service.missingCausalLinks("missing-order").shouldBeEmpty()
    }

    @Test
    fun `blank query ids fail fast`() {
        assertFailsWith<IllegalArgumentException> {
            service.eventsForAggregate(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            service.causalPath("order-approved", "", maxDepth = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            service.supersededChain("")
        }
    }

    @Test
    fun `bounded causal path returns empty result when maxDepth is too small`() {
        val path = service.causalPath(
            eventId = "order-approved",
            rootEventId = "order-created",
            maxDepth = 2,
        )

        path.nodes.shouldBeEmpty()
        path.edgeLabels.shouldBeEmpty()
    }

    @Test
    fun `addEvent rejects blank business identifiers`() {
        assertFailsWith<IllegalArgumentException> {
            service.addEvent("", "OrderCreated", "2026-07-02T01:00:00Z", "created")
        }
    }

    @Test
    fun `direct graph element lookup returns empty result for unknown vertex id`() {
        service.findEvent(GraphElementId("missing-vertex")).shouldBeEmpty()
    }
}
