package io.bluetape4k.workshop.shared.optimization

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import java.lang.AssertionError

class TravelTimeMatrixTest {

    private val depot = "depot"
    private val pickup = "pickup"

    @Test
    fun `lookup is constant time by coordinate pair and digest is deterministic`() {
        val first = matrix(
            edges = mapOf(
                CoordinatePair(pickup, depot) to 60L,
                CoordinatePair(depot, pickup) to 30L,
            ),
        )
        val second = matrix(
            edges = mapOf(
                CoordinatePair(depot, pickup) to 30L,
                CoordinatePair(pickup, depot) to 60L,
            ),
        )

        first.lookup(depot, pickup) shouldBeEqualTo 30L
        first.lookup("unknown", depot).shouldBeNull()
        first.edgeCount() shouldBeEqualTo 2
        first.digest() shouldBeEqualTo second.digest()
    }

    @Test
    fun `unknown edge and negative travel time are rejected`() {
        assertIllegalArgument {
            TravelTimeMatrix(
                revision = 1,
                coordinateIds = setOf(depot, pickup),
                edges = mapOf(CoordinatePair(depot, "unknown") to 1L),
            )
        }
        assertIllegalArgument {
            TravelTimeMatrix(
                revision = 1,
                coordinateIds = setOf(depot, pickup),
                edges = mapOf(CoordinatePair(depot, pickup) to -1L),
            )
        }
    }

    @Test
    fun `limits can reject empty and oversized matrices`() {
        assertIllegalArgument {
            TravelTimeMatrix<String>(
                revision = 0,
                coordinateIds = emptySet(),
                edges = emptyMap<CoordinatePair<String>, Long>(),
                limits = TravelTimeMatrixLimits(allowEmpty = false),
            )
        }
        assertIllegalArgument {
            matrix(limits = TravelTimeMatrixLimits(maxCoordinates = 1))
        }
    }

    private fun matrix(
        edges: Map<CoordinatePair<String>, Long> = emptyMap(),
        limits: TravelTimeMatrixLimits = TravelTimeMatrixLimits(),
    ): TravelTimeMatrix<String> = TravelTimeMatrix(
        revision = 7,
        coordinateIds = setOf(depot, pickup),
        edges = edges,
        limits = limits,
    )

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
