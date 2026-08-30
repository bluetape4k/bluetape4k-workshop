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
import io.bluetape4k.workshop.graph.eventlineage.model.LineageNode
import io.bluetape4k.workshop.graph.eventlineage.model.LineagePath
import io.bluetape4k.workshop.graph.eventlineage.service.EventLineageService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [EventLineageService]용 추상 테스트 suite입니다.
 *
 * seed 그래프는 aggregate, event, actor, decision 정점으로 주문 승인 상태를 설명합니다.
 * 구체 하위 클래스는 그래프 백엔드를 제공합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractEventLineageTest {

    protected abstract val graphName: String
    protected abstract val ops: GraphOperations
    protected abstract val service: EventLineageService

    protected lateinit var seed: EventLineageSeed

    @BeforeEach
    fun cleanGraph() {
        // bluetape4k-graph 0.6.0 requires selecting the logical graph before dropping it.
        ops.createGraph(graphName)
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
    fun `supersededChain respects max depth`() {
        val chain = service.supersededChain("order-approval-corrected", maxDepth = 1)

        chain.map { it.nodeId } shouldBeEqualTo listOf("order-approval-corrected")
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
        assertFailsWith<IllegalArgumentException> {
            service.supersededChain("order-approval-corrected", maxDepth = 0)
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
        service.findEvent(GraphElementId.of("99999999")).shouldBeEmpty()
    }

    @Test
    fun `lineage snapshots reject partial blanks and invalid path shapes`() {
        assertFailsWith<IllegalArgumentException> {
            LineageNode(nodeId = "", label = "")
        }
        assertFailsWith<IllegalArgumentException> {
            LineageNode(nodeId = "", label = EventLabel.label)
        }
        assertFailsWith<IllegalArgumentException> {
            LineageNode(nodeId = "event-1", label = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            LineagePath(nodes = listOf(LineageNode("event-1", EventLabel.label)), edgeLabels = listOf(CausedByLabel.label))
        }
        assertFailsWith<IllegalArgumentException> {
            LineagePath(
                nodes = listOf(LineageNode("event-1", EventLabel.label), LineageNode("event-2", EventLabel.label)),
                edgeLabels = listOf(" "),
            )
        }
    }
}
