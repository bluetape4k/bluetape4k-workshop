package io.bluetape4k.workshop.graph.eventlineage.model

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Reader-facing graph node snapshot with a stable domain identifier.
 */
@ConsistentCopyVisibility
data class LineageNode private constructor(
    val nodeId: String,
    val label: String,
    val properties: Map<String, Any?> = emptyMap(),
) : Serializable {
    init {
        if (nodeId.isBlank() || label.isBlank()) {
            nodeId.length.requireInRange(0, 0, "nodeId.length")
            label.length.requireInRange(0, 0, "label.length")
        } else {
            nodeId.requireNotBlank("nodeId")
            label.requireNotBlank("label")
        }
        properties.keys.forEach { it.requireNotBlank("properties.key") }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        val Empty: LineageNode = LineageNode("", "", emptyMap())

        operator fun invoke(
            nodeId: String,
            label: String,
            properties: Map<String, Any?> = emptyMap(),
        ): LineageNode {
            nodeId.requireNotBlank("nodeId")
            label.requireNotBlank("label")
            return LineageNode(nodeId, label, properties)
        }
    }
}

/**
 * Directed event lineage path.
 *
 * [nodes] are ordered from the current event toward the requested root event.
 * [edgeLabels] has one fewer entry than [nodes] and records the traversed edge
 * type for each hop.
 */
data class LineagePath(
    val nodes: List<LineageNode>,
    val edgeLabels: List<String>,
) : Serializable {
    init {
        val expectedEdgeCount = if (nodes.isEmpty()) 0 else nodes.size - 1
        edgeLabels.size.requireInRange(expectedEdgeCount, expectedEdgeCount, "edgeLabels.size")
        edgeLabels.forEach { it.requireNotBlank("edgeLabels") }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        val Empty: LineagePath = LineagePath(emptyList(), emptyList())
    }
}

/**
 * Approval evidence linking an event, decision, and deciding actor.
 */
data class ApprovalEvidence(
    val event: LineageNode,
    val decision: LineageNode,
    val actor: LineageNode,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Audit trail reconstructed for an aggregate.
 */
data class AggregateAuditTrail(
    val aggregate: LineageNode,
    val events: List<LineageNode>,
    val approvals: List<ApprovalEvidence>,
    val rootCauses: List<LineageNode>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        val Empty: AggregateAuditTrail = AggregateAuditTrail(
            aggregate = LineageNode.Empty,
            events = emptyList(),
            approvals = emptyList(),
            rootCauses = emptyList(),
        )
    }
}
