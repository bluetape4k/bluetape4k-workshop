package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.IdempotencyKey
import org.junit.jupiter.api.Test

class ShiftCoverageIdempotencyTest {
    @Test
    fun `same principal and fingerprint replays while other fingerprint is rejected`() {
        val store = ShiftCoverageIdempotencyStore()
        val namespace = IdempotencyNamespace("POST", "/swaps", "site-a", "worker-a", IdempotencyKey("key-1"))
        val first = store.begin(namespace, "a".repeat(64))
        first.kind shouldBeEqualTo IdempotencyClaimKind.NEW
        store.complete(namespace, "response-1")

        store.begin(namespace, "a".repeat(64)).kind shouldBeEqualTo IdempotencyClaimKind.REPLAY
        store.begin(namespace, "b".repeat(64)).kind shouldBeEqualTo IdempotencyClaimKind.REUSED
        store.begin(namespace.copy(principal = "worker-b"), "b".repeat(64)).kind shouldBeEqualTo IdempotencyClaimKind.NEW
        store.isWriteSuppressed(namespace, "b".repeat(64)).shouldBeTrue()
        store.isWriteSuppressed(namespace.copy(principal = "worker-b"), "b".repeat(64)).shouldBeTrue()
    }

    @Test
    fun `restarted repository can reuse the same durable map`() {
        val durable = mutableMapOf<IdempotencyNamespace, IdempotencyRecord>()
        val first = ShiftCoverageIdempotencyStore(durable)
        val second = ShiftCoverageIdempotencyStore(durable)
        val namespace = IdempotencyNamespace("POST", "/replans", "site-a", "manager", IdempotencyKey("key-2"))
        first.begin(namespace, "c".repeat(64))
        first.complete(namespace, "response-2")

        second.begin(namespace, "c".repeat(64)).response shouldBeEqualTo "response-2"
    }
}
