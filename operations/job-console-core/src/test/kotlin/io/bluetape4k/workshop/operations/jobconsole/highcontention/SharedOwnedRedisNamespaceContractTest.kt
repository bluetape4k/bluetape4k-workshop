package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.shared.redis.OwnedRedisKeyCommands
import io.bluetape4k.workshop.shared.redis.OwnedRedisNamespace
import io.bluetape4k.workshop.shared.redis.OwnedRedisScanPage
import io.bluetape4k.workshop.shared.redis.OwnedRedisWriterBarrier
import org.junit.jupiter.api.Test

class SharedOwnedRedisNamespaceContractTest {

    @Test
    fun `shared fixture applies policy and bounded deletion contract`() {
        val namespace = "hc:v1:run-1:job-core:redis-key-loss:"
        val ownedKey = "${namespace}cancel:job-1"
        val commands = RecordingCommands(
            pages = ArrayDeque(
                listOf(
                    OwnedRedisScanPage("0", listOf(ownedKey)),
                    OwnedRedisScanPage("0", emptyList()),
                ),
            ),
        )

        val result = OwnedRedisNamespace
            .parse(
                namespace = namespace,
                deleteUpperBound = 8,
                commands = commands,
                implementationPolicy = setOf("job-core")::contains,
            )
            .deleteOwnedKeys(OwnedRedisWriterBarrier.NONE)

        result.deletedKeys shouldBeEqualTo listOf(ownedKey)
        commands.unlinked shouldBeEqualTo listOf(listOf(ownedKey))

        assertFailsWith<IllegalArgumentException> {
            OwnedRedisNamespace.parse(
                namespace = "hc:v1:run-1:ticket-spring:redis-key-loss:",
                deleteUpperBound = 8,
                commands = commands,
                implementationPolicy = setOf("job-core")::contains,
            )
        }
    }

    private class RecordingCommands(
        private val pages: ArrayDeque<OwnedRedisScanPage>,
    ) : OwnedRedisKeyCommands {

        val unlinked = mutableListOf<List<String>>()

        override fun scan(
            cursor: String,
            pattern: String,
            count: Long,
        ): OwnedRedisScanPage = pages.removeFirst()

        override fun unlink(keys: List<String>): Long {
            unlinked += keys
            return keys.size.toLong()
        }
    }
}
