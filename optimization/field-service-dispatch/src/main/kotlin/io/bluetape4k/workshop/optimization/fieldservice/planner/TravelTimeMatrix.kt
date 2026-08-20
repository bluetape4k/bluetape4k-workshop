package io.bluetape4k.workshop.optimization.fieldservice.planner

import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
import java.io.Serializable

/** 불변 matrix edge의 pair key입니다. */
data class CoordinatePair(
    val from: CoordinateId,
    val to: CoordinateId,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** O(1) edge lookup을 제공하는 고정 revision travel-time matrix입니다. */
class TravelTimeMatrix(
    val revision: Long,
    coordinateIds: Set<CoordinateId>,
    edges: Map<CoordinatePair, Long>,
) : Serializable {
    val coordinateIds: Set<CoordinateId> = coordinateIds.toSet()
    private val edgeMap: Map<CoordinatePair, Long> = edges.toMap()

    init {
        require(revision >= 0L) { "matrix revision must be non-negative" }
        if (this.coordinateIds.size > FieldServiceLimits.MAX_COORDINATES) {
            throw InvalidFieldServiceInput("matrix coordinates exceed configured limit")
        }
        if (edgeMap.size > FieldServiceLimits.MAX_SPARSE_EDGES) {
            throw InvalidFieldServiceInput("matrix edges exceed configured limit")
        }
        edgeMap.forEach { (pair, duration) ->
            if (pair.from !in this.coordinateIds || pair.to !in this.coordinateIds) {
                throw InvalidFieldServiceInput("matrix edge references an unknown coordinate")
            }
            if (!FieldServiceLimits.isFiniteNonNegativeTravelTime(duration)) {
                throw InvalidFieldServiceInput("travel time must be finite and non-negative")
            }
        }
    }

    fun lookup(from: CoordinateId, to: CoordinateId): Long? = edgeMap[CoordinatePair(from, to)]

    fun updated(nextRevision: Long, nextEdges: Map<CoordinatePair, Long>): TravelTimeMatrix =
        TravelTimeMatrix(nextRevision, coordinateIds, nextEdges)

    fun edgeCount(): Int = edgeMap.size

    companion object {
        private const val serialVersionUID = 1L
    }
}
