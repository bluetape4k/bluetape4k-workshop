package io.bluetape4k.workshop.graph.eventlineage.model

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * 안정적인 도메인 식별자를 가진 독자 대상 그래프 노드 스냅샷입니다.
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
 * 방향이 있는 이벤트 lineage 경로입니다.
 *
 * [nodes]는 현재 이벤트에서 요청한 root 이벤트 방향으로 정렬됩니다.
 * [edgeLabels]는 [nodes]보다 항목이 하나 적으며, 각 hop에서 순회한 간선
 * 유형을 기록합니다.
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
 * 이벤트, 결정, 결정 주체를 연결하는 승인 증거입니다.
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
 * aggregate에 대해 재구성한 감사 추적입니다.
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
