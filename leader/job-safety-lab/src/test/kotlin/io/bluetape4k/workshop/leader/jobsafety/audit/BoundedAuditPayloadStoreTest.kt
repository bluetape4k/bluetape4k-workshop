package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import org.junit.jupiter.api.Test

internal class BoundedAuditPayloadStoreTest {

    companion object: KLogging()

    @Test
    fun `store retains only serialized bytes and evicts by exact byte budget`() {
        val store = BoundedAuditPayloadStore(maxEntries = 2, maxBytes = 8)

        store.add("1234".toUtf8Bytes())
        store.add("5678".toUtf8Bytes())
        store.add("90".toUtf8Bytes())

        store.snapshot().map { it.decodeToString() } shouldBeEqualTo listOf("5678", "90")
        store.retainedBytes shouldBeEqualTo 6L
        store.size shouldBeEqualTo 2
    }

    @Test
    fun `store copies input and snapshot bytes defensively`() {
        val store = BoundedAuditPayloadStore(maxEntries = 2, maxBytes = 32)
        val input = "payload".toUtf8Bytes()

        store.add(input).shouldBeTrue()
        input[0] = 'X'.code.toByte()

        val snapshot = store.snapshot()
        snapshot.single()[0] = 'Y'.code.toByte()

        store.snapshot().single().decodeToString() shouldBeEqualTo "payload"
    }

    @Test
    fun `store drops payload larger than byte budget without eviction`() {
        val store = BoundedAuditPayloadStore(maxEntries = 2, maxBytes = 4)
        store.add("ok".toUtf8Bytes()).shouldBeTrue()

        store.add("12345".toUtf8Bytes()).shouldBeFalse()

        store.snapshot().map { it.decodeToString() } shouldBeEqualTo listOf("ok")
        store.retainedBytes shouldBeEqualTo 2L
    }
}
