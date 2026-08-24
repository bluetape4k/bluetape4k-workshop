package io.bluetape4k.workshop.optimization.lastmile.planner

import io.bluetape4k.workshop.optimization.lastmile.domain.CoordinateId
import io.bluetape4k.workshop.optimization.lastmile.domain.InvalidLastMileInput
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileLimits
import java.security.MessageDigest

data class CoordinatePair(
    val from: CoordinateId,
    val to: CoordinateId,
)

class TravelTimeMatrix(
    val revision: Long,
    coordinateIds: Set<CoordinateId>,
    edges: Map<CoordinatePair, Long>,
) {
    val coordinateIds: Set<CoordinateId> = coordinateIds.toSet()
    private val edgeMap: Map<CoordinatePair, Long> = edges.toMap()

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
    }

    fun lookup(from: CoordinateId, to: CoordinateId): Long? = edgeMap[CoordinatePair(from, to)]

    fun edgeCount(): Int = edgeMap.size

    fun digest(): String = MessageDigest.getInstance("SHA-256")
        .digest(
            buildString {
                append(revision)
                coordinateIds.sortedBy { it.value }.forEach { append('|').append(it.value) }
                edgeMap.entries.sortedWith(compareBy({ it.key.from.value }, { it.key.to.value }))
                    .forEach { (pair, seconds) -> append('|').append(pair.from.value).append('>').append(pair.to.value).append('=').append(seconds) }
            }.toByteArray(),
        ).joinToString("") { "%02x".format(it) }
}
