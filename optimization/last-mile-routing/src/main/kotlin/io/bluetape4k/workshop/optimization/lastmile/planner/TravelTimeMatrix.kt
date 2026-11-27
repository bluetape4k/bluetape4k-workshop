package io.bluetape4k.workshop.optimization.lastmile.planner

import io.bluetape4k.workshop.optimization.lastmile.domain.CoordinateId
import io.bluetape4k.workshop.optimization.lastmile.domain.InvalidLastMileInput
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileLimits
import io.bluetape4k.workshop.shared.optimization.CoordinatePair as SharedCoordinatePair
import io.bluetape4k.workshop.shared.optimization.TravelTimeMatrix as SharedTravelTimeMatrix
import io.bluetape4k.workshop.shared.optimization.TravelTimeMatrixLimits

data class CoordinatePair(
    val from: CoordinateId,
    val to: CoordinateId,
)

/** shared sparse matrix primitive를 last-mile 도메인 예외와 상한으로 감싸는 adapter입니다. */
class TravelTimeMatrix(
    val revision: Long,
    coordinateIds: Set<CoordinateId>,
    edges: Map<CoordinatePair, Long>,
) {
    val coordinateIds: Set<CoordinateId> = coordinateIds.toSet()
    private val edgeMap: Map<CoordinatePair, Long> = edges.toMap()

    private val commonMatrix: SharedTravelTimeMatrix<CoordinateId>

    init {
        require(revision >= 0L) { "matrix revision must be non-negative" }
        require(this.coordinateIds.isNotEmpty()) { "matrix must contain coordinates" }
        require(this.coordinateIds.size <= LastMileLimits.MAX_MATRIX_COORDINATES) {
            "matrix coordinates exceed configured limit"
        }
        require(edgeMap.size <= LastMileLimits.MAX_MATRIX_EDGES) { "matrix edges exceed configured limit" }

        edgeMap.forEach { (pair, seconds) ->
            require(pair.from in this.coordinateIds && pair.to in this.coordinateIds) {
                "matrix edge references an unknown coordinate"
            }
            if (seconds < 0L) {
                throw InvalidLastMileInput("travel time must be finite and non-negative")
            }
        }

        commonMatrix = SharedTravelTimeMatrix(
            revision = revision,
            coordinateIds = this.coordinateIds,
            edges = edgeMap.mapKeys { (pair, _) -> SharedCoordinatePair(pair.from, pair.to) },
            idText = CoordinateId::value,
            limits = TravelTimeMatrixLimits(
                maxCoordinates = LastMileLimits.MAX_MATRIX_COORDINATES,
                maxEdges = LastMileLimits.MAX_MATRIX_EDGES,
                allowEmpty = false,
            ),
        )
    }

    fun lookup(from: CoordinateId, to: CoordinateId): Long? = edgeMap[CoordinatePair(from, to)]

    fun edgeCount(): Int = edgeMap.size

    fun digest(): String = commonMatrix.digest()
}
