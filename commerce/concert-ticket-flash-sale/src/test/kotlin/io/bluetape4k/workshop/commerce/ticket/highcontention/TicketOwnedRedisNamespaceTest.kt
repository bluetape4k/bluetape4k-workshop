package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

class TicketOwnedRedisNamespaceTest {

    @Test
    fun `ticket owner parser consumes every golden vector case and canonical digest`() {
        val document = redisVectorDocument()
        canonicalDigest(document.get("vectors"))
            .shouldBeEqualTo(document.get("vectorsSha256").stringValue())
        document.get("vectorsSha256").stringValue().shouldBeEqualTo(CANONICAL_VECTOR_DIGEST)
        val vector = document.get("vectors")
            .first { it.get("name").stringValue() == "ticket-owned-namespace" }
        val namespace = TicketOwnedRedisNamespace.parse(
            namespace = vector.get("namespace").stringValue(),
            deleteUpperBound = vector.get("deleteUpperBound").asInt(),
            commands = RecordingTicketRedisKeyCommands(),
        )

        vector.get("cases").forEach { case ->
            namespace.owns(case.get("key").stringValue())
                .shouldBeEqualTo(case.get("expectedOwned").asBoolean())
        }
    }

    @Test
    fun `empty unknown and non-terminal namespace components fail closed`() {
        listOf(
            "",
            "hc:v1::ticket-spring:redis-key-loss:",
            "hc:v1:run-1:unknown:redis-key-loss:",
            "hc:v1:run-1:ticket-spring:unknown:",
            "hc:v2:run-1:ticket-spring:redis-key-loss:",
            "hc:v1:run-1:ticket-spring:redis-key-loss",
        ).forEach { namespace ->
            assertFailsWith<IllegalArgumentException> {
                TicketOwnedRedisNamespace.parse(namespace, 8, RecordingTicketRedisKeyCommands())
            }
        }
    }

    @Test
    fun `writer is paused while all candidates are validated before bounded unlink`() {
        val namespaceValue = "hc:v1:run-1:ticket-spring:redis-key-loss:"
        val commands = RecordingTicketRedisKeyCommands(
            pages = ArrayDeque(
                listOf(
                    TicketOwnedRedisScanPage("7", listOf("${namespaceValue}lease:user-2")),
                    TicketOwnedRedisScanPage("0", listOf("${namespaceValue}lease:user-1")),
                    TicketOwnedRedisScanPage("0", emptyList()),
                ),
            ),
        )
        val barrierEvents = mutableListOf<String>()
        val namespace = TicketOwnedRedisNamespace.parse(namespaceValue, 8, commands)

        val result = namespace.deleteOwnedKeys {
            barrierEvents += "paused"
            AutoCloseable { barrierEvents += "resumed" }
        }

        result.deletedKeys shouldBeEqualTo listOf(
            "${namespaceValue}lease:user-1",
            "${namespaceValue}lease:user-2",
        )
        commands.unlinked shouldBeEqualTo listOf(result.deletedKeys)
        barrierEvents shouldBeEqualTo listOf("paused", "resumed")
    }

    @Test
    fun `foreign candidates and delete upper bound fail before unlink`() {
        val namespaceValue = "hc:v1:run-1:ticket-spring:redis-key-loss:"
        listOf(
            listOf("${namespaceValue}lease:user-1", "hc:v1:foreign:sentinel"),
            (1..9).map { "${namespaceValue}lease:user-$it" },
        ).forEach { keys ->
            val commands = RecordingTicketRedisKeyCommands(
                pages = ArrayDeque(listOf(TicketOwnedRedisScanPage("0", keys))),
            )
            val namespace = TicketOwnedRedisNamespace.parse(namespaceValue, 8, commands)

            assertFailsWith<IllegalStateException> {
                namespace.deleteOwnedKeys(TicketOwnedRedisWriterBarrier.NONE)
            }
            commands.unlinked shouldBeEqualTo emptyList()
        }
    }

    @Test
    fun `incomplete scan and post-unlink non-convergence fail closed`() {
        val namespaceValue = "hc:v1:run-1:ticket-spring:redis-key-loss:"
        val incomplete = RecordingTicketRedisKeyCommands(
            pages = ArrayDeque(
                listOf(
                    TicketOwnedRedisScanPage("1", emptyList()),
                    TicketOwnedRedisScanPage("1", emptyList()),
                    TicketOwnedRedisScanPage("1", emptyList()),
                ),
            ),
            repeatLastPage = true,
        )
        assertFailsWith<IllegalStateException> {
            TicketOwnedRedisNamespace.parse(
                namespaceValue,
                8,
                incomplete,
                maximumScanIterations = 2,
            ).deleteOwnedKeys(TicketOwnedRedisWriterBarrier.NONE)
        }

        val ownedKey = "${namespaceValue}lease:user-1"
        val nonConverging = RecordingTicketRedisKeyCommands(
            pages = ArrayDeque(
                listOf(
                    TicketOwnedRedisScanPage("0", listOf(ownedKey)),
                    TicketOwnedRedisScanPage("0", listOf(ownedKey)),
                ),
            ),
        )
        assertFailsWith<IllegalStateException> {
            TicketOwnedRedisNamespace.parse(namespaceValue, 8, nonConverging)
                .deleteOwnedKeys(TicketOwnedRedisWriterBarrier.NONE)
        }
        nonConverging.unlinked shouldBeEqualTo listOf(listOf(ownedKey))
    }

    @Test
    fun `real Redis scan unlinks only the run owned namespace`() {
        val redis = RedisServer.Launcher.redis
        val client = LettuceClients.clientOf(redis.url)
        val connection = LettuceClients.connect(client)
        val commands = connection.sync()
        val runId = UUID.randomUUID().toString()
        val namespaceValue = "hc:v1:$runId:ticket-spring:redis-key-loss:"
        val ownedKeys = listOf("${namespaceValue}lease:user-1", "${namespaceValue}lease:user-2")
        val foreignKey = "hc:v1:foreign:sentinel:$runId"

        try {
            (ownedKeys + foreignKey).forEach { commands.set(it, "1") }
            val result = TicketOwnedRedisNamespace.parse(namespaceValue, 8, commands)
                .deleteOwnedKeys(TicketOwnedRedisWriterBarrier.NONE)

            result.deletedKeys shouldBeEqualTo ownedKeys
            commands.exists(*ownedKeys.toTypedArray()) shouldBeEqualTo 0L
            commands.exists(foreignKey) shouldBeEqualTo 1L
        } finally {
            commands.unlink(*(ownedKeys + foreignKey).toTypedArray())
            connection.close()
            LettuceClients.shutdown(client)
        }
    }

    private fun redisVectorDocument(): JsonNode {
        val contractRoot = Path.of(
            System.getProperty("highContentionContractRoot").requireNotNull("highContentionContractRoot"),
        )
        return Files.newInputStream(contractRoot.resolve("redis-key-vectors.json")).use {
            JSON_MAPPER.readTree(it)
        }
    }

    private fun canonicalDigest(node: JsonNode): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson(node).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun canonicalJson(node: JsonNode): String =
        when {
            node.isArray -> node.joinToString(separator = ",", prefix = "[", postfix = "]", transform = ::canonicalJson)
            node.isObject -> node.properties()
                .sortedBy { it.key }
                .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                    "${JSON_MAPPER.writeValueAsString(key)}:${canonicalJson(value)}"
                }
            else -> node.toString()
        }

    private companion object {
        const val CANONICAL_VECTOR_DIGEST = "406278bff9c644546cadea9ffc9919a36f1066d5958043fc196269faa6c8a774"
        val JSON_MAPPER = Jackson.createDefaultJsonMapper()
    }
}

private class RecordingTicketRedisKeyCommands(
    private val pages: ArrayDeque<TicketOwnedRedisScanPage> = ArrayDeque(),
    private val repeatLastPage: Boolean = false,
) : TicketOwnedRedisKeyCommands {
    val unlinked = mutableListOf<List<String>>()
    private var lastPage = TicketOwnedRedisScanPage("0", emptyList())

    override fun scan(
        cursor: String,
        pattern: String,
        count: Long,
    ): TicketOwnedRedisScanPage {
        if (pages.isNotEmpty()) {
            lastPage = pages.removeFirst()
        } else if (!repeatLastPage) {
            lastPage = TicketOwnedRedisScanPage("0", emptyList())
        }
        return lastPage
    }

    override fun unlink(keys: List<String>): Long {
        unlinked += keys
        return keys.size.toLong()
    }
}
