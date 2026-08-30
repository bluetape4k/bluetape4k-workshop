package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

internal class BoundedAuditPayloadStoreTest {

    @Test
    fun `store retains only serialized bytes and evicts by exact byte budget`() {
        val store = BoundedAuditPayloadStore(maxEntries = 2, maxBytes = 8)

        store.add("1234".toByteArray())
        store.add("5678".toByteArray())
        store.add("90".toByteArray())

        store.snapshot().map { it.decodeToString() } shouldBeEqualTo listOf("5678", "90")
        store.retainedBytes shouldBeEqualTo 6L
        store.size shouldBeEqualTo 2
    }

    @Test
    fun `store copies input and snapshot bytes defensively`() {
        val store = BoundedAuditPayloadStore(maxEntries = 2, maxBytes = 32)
        val input = "payload".toByteArray()

        store.add(input).shouldBeTrue()
        input[0] = 'X'.code.toByte()

        val snapshot = store.snapshot()
        snapshot.single()[0] = 'Y'.code.toByte()

        store.snapshot().single().decodeToString() shouldBeEqualTo "payload"
    }

    @Test
    fun `store drops payload larger than byte budget without eviction`() {
        val store = BoundedAuditPayloadStore(maxEntries = 2, maxBytes = 4)
        store.add("ok".toByteArray()).shouldBeTrue()

        store.add("12345".toByteArray()).shouldBeFalse()

        store.snapshot().map { it.decodeToString() } shouldBeEqualTo listOf("ok")
        store.retainedBytes shouldBeEqualTo 2L
    }
}
