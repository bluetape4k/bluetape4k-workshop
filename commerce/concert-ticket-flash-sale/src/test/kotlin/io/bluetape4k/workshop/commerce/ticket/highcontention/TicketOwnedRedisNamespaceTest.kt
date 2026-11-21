package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * TicketOwnedRedisNamespaceTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class TicketOwnedRedisNamespaceTest {

    private companion object: KLogging() {
/**
 * CANONICAL_VECTOR_DIGEST은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val CANONICAL_VECTOR_DIGEST = "406278bff9c644546cadea9ffc9919a36f1066d5958043fc196269faa6c8a774"
/**
 * JSON_MAPPER은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val JSON_MAPPER = Jackson.defaultJsonMapper
    }

    @Test
/**
 * 이 시나리오는 "ticket owner parser consumes every golden vector case and canonical digest" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `ticket owner parser consumes every golden vector case and canonical digest`() {
        val document = redisVectorDocument()
        log.debug { "document: $document" }
        canonicalDigest(document.get("vectors")) shouldBeEqualTo document.get("vectorsSha256").stringValue()
        document.get("vectorsSha256").stringValue().shouldBeEqualTo(CANONICAL_VECTOR_DIGEST)

        val vector = document.get("vectors").first { it.get("name").stringValue() == "ticket-owned-namespace" }

        val namespace = TicketOwnedRedisNamespace.parse(
            namespace = vector.get("namespace").stringValue(),
            deleteUpperBound = vector.get("deleteUpperBound").asInt(),
            commands = RecordingTicketRedisKeyCommands(),
        )

        vector.get("cases").forEach { case ->
            log.debug { "case: $case" }
            namespace.owns(case.get("key").stringValue()) shouldBeEqualTo case.get("expectedOwned").asBoolean()
        }
    }

    @Test
/**
 * 이 시나리오는 "empty unknown and non-terminal namespace components fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "writer is paused while all candidates are validated before bounded unlink" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "foreign candidates and delete upper bound fail before unlink" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
            commands.unlinked.shouldBeEmpty()
        }
    }

    @Test
/**
 * 이 시나리오는 "incomplete scan and post-unlink non-convergence fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
            TicketOwnedRedisNamespace
                .parse(namespaceValue, 8, nonConverging)
                .deleteOwnedKeys(TicketOwnedRedisWriterBarrier.NONE)
        }
        nonConverging.unlinked shouldBeEqualTo listOf(listOf(ownedKey))
    }

    @Test
/**
 * 이 시나리오는 "real Redis scan unlinks only the run owned namespace" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `real Redis scan unlinks only the run owned namespace`() {
        val redis = RedisServer.Launcher.redis
        val client = LettuceClients.clientOf(redis.url)
        val connection = LettuceClients.connect(client)
        val commands = connection.sync()
        val runId = Base58.randomString(16)
        val namespaceValue = "hc:v1:$runId:ticket-spring:redis-key-loss:"
        val ownedKeys = listOf("${namespaceValue}lease:user-1", "${namespaceValue}lease:user-2")
        val foreignKey = "hc:v1:foreign:sentinel:$runId"

        try {
            (ownedKeys + foreignKey).forEach { commands.set(it, "1") }
            val result = TicketOwnedRedisNamespace
                .parse(namespaceValue, 8, commands)
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

/**
 * redisVectorDocument은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun redisVectorDocument(): JsonNode {
        val contractRoot = Path.of(
            System.getProperty("highContentionContractRoot").requireNotNull("highContentionContractRoot"),
        )
        return Files.newInputStream(contractRoot.resolve("redis-key-vectors.json"))
            .use {
                JSON_MAPPER.readTree(it)
            }
    }

/**
 * canonicalDigest은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun canonicalDigest(node: JsonNode): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson(node).toUtf8Bytes())
            .joinToString("") { "%02x".format(it) }

/**
 * canonicalJson은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun canonicalJson(node: JsonNode): String =
        when {
            node.isArray -> node.joinToString(
                separator = ",",
                prefix = "[",
                postfix = "]",
                transform = ::canonicalJson
            )
            node.isObject -> node.properties()
                .sortedBy { it.key }
                .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                    "${JSON_MAPPER.writeValueAsString(key)}:${canonicalJson(value)}"
                }
            else         -> node.toString()
        }
}

/**
 * RecordingTicketRedisKeyCommands 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class RecordingTicketRedisKeyCommands(
/**
 * pages은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val pages: ArrayDeque<TicketOwnedRedisScanPage> = ArrayDeque(),
/**
 * repeatLastPage은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repeatLastPage: Boolean = false,
): TicketOwnedRedisKeyCommands {
/**
 * unlinked은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val unlinked = mutableListOf<List<String>>()
/**
 * lastPage은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var lastPage = TicketOwnedRedisScanPage("0", emptyList())

/**
 * scan은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * unlink은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun unlink(keys: List<String>): Long {
        unlinked += keys
        return keys.size.toLong()
    }
}
