package io.bluetape4k.workshop.shared.optimization

import io.bluetape4k.workshop.shared.fingerprint.FingerprintEncoder

/** 희소 이동시간 행렬의 방향성 있는 좌표 쌍입니다. */
data class CoordinatePair<ID>(val from: ID, val to: ID)

/** 행렬 생성 시 소비자가 선택하는 공통 크기와 empty 정책입니다. */
data class TravelTimeMatrixLimits(
    val maxCoordinates: Int = Int.MAX_VALUE,
    val maxEdges: Int = Int.MAX_VALUE,
    val allowEmpty: Boolean = true,
) {
    init {
        require(maxCoordinates >= 0) { "maxCoordinates must be non-negative" }
        require(maxEdges >= 0) { "maxEdges must be non-negative" }
    }
}

/** O(1) edge lookup과 순서 독립적인 digest를 제공하는 immutable 희소 행렬입니다. */
class TravelTimeMatrix<ID>(
    val revision: Long,
    coordinateIds: Set<ID>,
    edges: Map<CoordinatePair<ID>, Long>,
    private val idText: (ID) -> String = { it.toString() },
    val limits: TravelTimeMatrixLimits = TravelTimeMatrixLimits(),
) {

    val coordinateIds: Set<ID> = coordinateIds.toSet()

    private val edgeValues: Map<CoordinatePair<ID>, Long> = edges.toMap()

    init {
        require(revision >= 0) { "revision must be non-negative" }
        require(limits.allowEmpty || this.coordinateIds.isNotEmpty()) {
            "coordinateIds must not be empty"
        }
        require(this.coordinateIds.size <= limits.maxCoordinates) {
            "coordinate count exceeds maxCoordinates"
        }
        require(edgeValues.size <= limits.maxEdges) { "edge count exceeds maxEdges" }
        edgeValues.forEach { (pair, travelTime) ->
            require(pair.from in this.coordinateIds && pair.to in this.coordinateIds) {
                "edge coordinates must be present in coordinateIds"
            }
            require(travelTime >= 0) { "travelTime must be non-negative" }
        }
    }

    /** 좌표 쌍의 이동시간을 조회하며 미등록 edge는 null을 반환합니다. */
    fun lookup(from: ID, to: ID): Long? = edgeValues[CoordinatePair(from, to)]

    /** 등록된 방향성 edge 수를 반환합니다. */
    fun edgeCount(): Int = edgeValues.size

    /** 좌표와 edge 입력 순서에 영향을 받지 않는 deterministic digest를 반환합니다. */
    fun digest(): String {
        val material = buildString {
            append(revision)
            coordinateIds.sortedWith(compareBy(idText)).forEach { coordinate ->
                append('|').append(idText(coordinate))
            }
            edgeValues.entries
                .sortedWith(
                    compareBy<Map.Entry<CoordinatePair<ID>, Long>> { idText(it.key.from) }
                        .thenBy { idText(it.key.to) },
                ).forEach { (pair, travelTime) ->
                    append('|').append(idText(pair.from))
                    append('>').append(idText(pair.to))
                    append('=').append(travelTime)
                }
        }
        return FingerprintEncoder.sha256Hex(material)
    }
}
