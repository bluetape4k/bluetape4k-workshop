package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireEndsWith
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisCommands
import java.util.*

/**
 * TicketOwnedRedisScanPage 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class TicketOwnedRedisScanPage(
/**
 * nextCursor은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val nextCursor: String,
/**
 * keys은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val keys: List<String>,
)

/**
 * TicketOwnedRedisKeyCommands 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
interface TicketOwnedRedisKeyCommands {

/**
 * scan은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun scan(
        cursor: String,
        pattern: String,
        count: Long,
    ): TicketOwnedRedisScanPage

/**
 * unlink은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun unlink(keys: List<String>): Long
}

/**
 * TicketOwnedRedisWriterBarrier 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
fun interface TicketOwnedRedisWriterBarrier {

/**
 * pause은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun pause(): AutoCloseable

    companion object {
/**
 * NONE은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NONE: TicketOwnedRedisWriterBarrier = TicketOwnedRedisWriterBarrier { AutoCloseable {} }
    }
}

/**
 * TicketOwnedRedisDeletionResult 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class TicketOwnedRedisDeletionResult(
/**
 * deletedKeys은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deletedKeys: List<String>,
)

/**
 * TicketOwnedRedisNamespace 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class TicketOwnedRedisNamespace private constructor(
/**
 * namespace은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val namespace: String,
/**
 * deleteUpperBound은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val deleteUpperBound: Int,
/**
 * commands은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val commands: TicketOwnedRedisKeyCommands,
/**
 * maximumScanIterations은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val maximumScanIterations: Int,
) {

/**
 * owns은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun owns(key: String): Boolean {
        if (!key.startsWith(namespace)) {
            return false
        }
        val suffixComponents = key.removePrefix(namespace).split(':')
        return suffixComponents.size == OWNED_KEY_COMPONENT_COUNT &&
                suffixComponents.all(OWNER_COMPONENT_PATTERN::matches)
    }

/**
 * deleteOwnedKeys은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun deleteOwnedKeys(
        writerBarrier: TicketOwnedRedisWriterBarrier,
    ): TicketOwnedRedisDeletionResult =
        writerBarrier.pause().use {
/**
 * candidates은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val candidates = scanAll()
            check(candidates.all(::owns)) {
                "Redis SCAN returned a key outside the owned namespace."
            }
            check(candidates.size <= deleteUpperBound) {
                "Owned Redis key count[${candidates.size}] exceeds delete upper bound[$deleteUpperBound]."
            }

            if (candidates.isNotEmpty()) {
/**
 * unlinkedKeyCount은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val unlinkedKeyCount = commands.unlink(candidates)
                check(unlinkedKeyCount == candidates.size.toLong()) {
                    "Redis UNLINK removed $unlinkedKeyCount of ${candidates.size} owned keys."
                }
            }

            check(scanAll().isEmpty()) {
                "Owned Redis namespace did not converge after UNLINK."
            }
            TicketOwnedRedisDeletionResult(candidates)
        }

/**
 * scanAll은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun scanAll(): List<String> {
        val candidates = TreeSet<String>()
        var cursor = INITIAL_CURSOR
        var scanIterations = 0

        do {
            check(++scanIterations <= maximumScanIterations) {
                "Redis SCAN did not converge within $maximumScanIterations iterations."
            }
            val page = commands.scan(
                cursor = cursor,
                pattern = "$namespace*",
                count = deleteUpperBound.toLong(),
            )
            candidates += page.keys
            cursor = page.nextCursor
        } while (cursor != INITIAL_CURSOR)

        return candidates.toList()
    }

    companion object: KLogging() {
/**
 * INITIAL_CURSOR은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val INITIAL_CURSOR = "0"
/**
 * NAMESPACE_COMPONENT_COUNT은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val NAMESPACE_COMPONENT_COUNT = 5
/**
 * OWNED_KEY_COMPONENT_COUNT은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val OWNED_KEY_COMPONENT_COUNT = 2
/**
 * OWNER_COMPONENT_PATTERN은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val OWNER_COMPONENT_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

/**
 * parse은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun parse(
            namespace: String,
            deleteUpperBound: Int,
            commands: RedisCommands<String, String>,
            maximumScanIterations: Int = 1_024,
        ): TicketOwnedRedisNamespace =
            parse(
                namespace = namespace,
                deleteUpperBound = deleteUpperBound,
                commands = LettuceTicketOwnedRedisKeyCommands(commands),
                maximumScanIterations = maximumScanIterations,
            )

/**
 * parse은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun parse(
            namespace: String,
            deleteUpperBound: Int,
            commands: TicketOwnedRedisKeyCommands,
            maximumScanIterations: Int = 1_024,
        ): TicketOwnedRedisNamespace {
/**
 * validatedNamespace은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val validatedNamespace = namespace.requireNotBlank("namespace")
            validatedNamespace.requireEndsWith(":") {
                "namespace[$validatedNamespace] must end with a terminal delimiter."
            }
/**
 * components은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val components = validatedNamespace.dropLast(1).split(':')
            components.size.requireEquals(NAMESPACE_COMPONENT_COUNT, "namespaceComponentCount")
            components[0].requireEquals("hc", "namespaceRoot")
            components[1].requireEquals("v1", "namespaceVersion")

            require(OWNER_COMPONENT_PATTERN.matches(components[2])) {
                "runId[${components[2]}] must be a valid namespace component."
            }
            components[3].requireEquals("ticket-spring", "implementation")
            components[4].requireEquals("redis-key-loss", "profileId")

            return TicketOwnedRedisNamespace(
                namespace = validatedNamespace,
                deleteUpperBound = deleteUpperBound.requirePositiveNumber("deleteUpperBound"),
                commands = commands,
                maximumScanIterations = maximumScanIterations.requirePositiveNumber("maximumScanIterations"),
            )
        }
    }
}

/**
 * LettuceTicketOwnedRedisKeyCommands 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class LettuceTicketOwnedRedisKeyCommands(
/**
 * commands은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val commands: RedisCommands<String, String>,
): TicketOwnedRedisKeyCommands {

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
/**
 * page은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val page = commands.scan(
            ScanCursor.of(cursor),
            ScanArgs().match(pattern).limit(count),
        )
        return TicketOwnedRedisScanPage(
            nextCursor = page.cursor,
            keys = page.keys,
        )
    }

/**
 * unlink은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun unlink(keys: List<String>): Long =
        commands.unlink(*keys.toTypedArray())
}
