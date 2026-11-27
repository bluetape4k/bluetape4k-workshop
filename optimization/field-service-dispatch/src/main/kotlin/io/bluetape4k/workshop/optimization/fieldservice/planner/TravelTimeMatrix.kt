package io.bluetape4k.workshop.optimization.fieldservice.planner

import io.bluetape4k.workshop.optimization.fieldservice.domain.CoordinateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
import io.bluetape4k.workshop.shared.optimization.CoordinatePair as SharedCoordinatePair
import io.bluetape4k.workshop.shared.optimization.TravelTimeMatrix as SharedTravelTimeMatrix
import io.bluetape4k.workshop.shared.optimization.TravelTimeMatrixLimits
import java.io.Serializable

/** 불변 matrix edge의 field-service 전용 pair key입니다. */
data class CoordinatePair(
    val from: CoordinateId,
    val to: CoordinateId,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** shared sparse matrix primitive를 domain 예외와 상한으로 감싸는 field-service adapter입니다. */
class TravelTimeMatrix(
    val revision: Long,
    coordinateIds: Set<CoordinateId>,
    edges: Map<CoordinatePair, Long>,
): Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    val coordinateIds: Set<CoordinateId> = coordinateIds.toSet()
    private val edgeMap: Map<CoordinatePair, Long> = edges.toMap()

    @Transient
    private var commonMatrix: SharedTravelTimeMatrix<CoordinateId>? = null

    init {
        if (revision < 0L) {
            throw InvalidFieldServiceInput("revision must be non-negative")
        }
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

    /** shared primitive의 deterministic matrix digest를 노출합니다. */
    fun digest(): String = sharedMatrix().digest()

    private fun sharedMatrix(): SharedTravelTimeMatrix<CoordinateId> =
        commonMatrix ?: SharedTravelTimeMatrix(
            revision = revision,
            coordinateIds = coordinateIds,
            edges = edgeMap.mapKeys { (pair, _) -> SharedCoordinatePair(pair.from, pair.to) },
            idText = CoordinateId::value,
            limits = TravelTimeMatrixLimits(
                maxCoordinates = FieldServiceLimits.MAX_COORDINATES,
                maxEdges = FieldServiceLimits.MAX_SPARSE_EDGES,
            ),
        ).also { commonMatrix = it }
}
