package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisCommands
import java.util.TreeSet

/**
 * OwnedRedisScanPage 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class OwnedRedisScanPage(
/**
 * nextCursor은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val nextCursor: String,
/**
 * keys은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val keys: List<String>,
)

/**
 * OwnedRedisKeyCommands 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
interface OwnedRedisKeyCommands {

/**
 * scan은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun scan(
        cursor: String,
        pattern: String,
        count: Long,
    ): OwnedRedisScanPage

/**
 * unlink은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun unlink(keys: List<String>): Long
}

/**
 * OwnedRedisWriterBarrier 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
fun interface OwnedRedisWriterBarrier {

/**
 * pause은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun pause(): AutoCloseable

    companion object {
/**
 * NONE은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NONE: OwnedRedisWriterBarrier = OwnedRedisWriterBarrier { AutoCloseable {} }
    }
}

/**
 * OwnedRedisDeletionResult 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class OwnedRedisDeletionResult(
/**
 * deletedKeys은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deletedKeys: List<String>,
)

/**
 * OwnedRedisNamespace 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class OwnedRedisNamespace private constructor(
/**
 * namespace은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val namespace: String,
/**
 * deleteUpperBound은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val deleteUpperBound: Int,
/**
 * commands은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val commands: OwnedRedisKeyCommands,
/**
 * maximumScanIterations은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val maximumScanIterations: Int,
) {

/**
 * owns은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
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
 * deleteOwnedKeys은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun deleteOwnedKeys(
        writerBarrier: OwnedRedisWriterBarrier,
    ): OwnedRedisDeletionResult =
        writerBarrier.pause().use {
/**
 * candidates은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
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
 * unlinkedKeyCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
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
            OwnedRedisDeletionResult(candidates)
        }

/**
 * scanAll은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
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

    companion object {
/**
 * INITIAL_CURSOR은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val INITIAL_CURSOR = "0"
/**
 * NAMESPACE_COMPONENT_COUNT은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val NAMESPACE_COMPONENT_COUNT = 5
/**
 * OWNED_KEY_COMPONENT_COUNT은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val OWNED_KEY_COMPONENT_COUNT = 2
/**
 * OWNER_COMPONENT_PATTERN은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val OWNER_COMPONENT_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
/**
 * JOB_IMPLEMENTATIONS은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val JOB_IMPLEMENTATIONS = setOf("job-core", "job-spring", "job-ktor")

/**
 * parse은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun parse(
            namespace: String,
            deleteUpperBound: Int,
            commands: RedisCommands<String, String>,
            maximumScanIterations: Int = 1_024,
        ): OwnedRedisNamespace =
            parse(
                namespace = namespace,
                deleteUpperBound = deleteUpperBound,
                commands = LettuceOwnedRedisKeyCommands(commands),
                maximumScanIterations = maximumScanIterations,
            )

/**
 * parse은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun parse(
            namespace: String,
            deleteUpperBound: Int,
            commands: OwnedRedisKeyCommands,
            maximumScanIterations: Int = 1_024,
        ): OwnedRedisNamespace {
/**
 * validatedNamespace은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val validatedNamespace = namespace.requireNotBlank("namespace")
            require(validatedNamespace.endsWith(':')) {
                "namespace[$validatedNamespace] must end with a terminal delimiter."
            }
/**
 * components은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
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
            require(components[3] in JOB_IMPLEMENTATIONS) {
                "implementation[${components[3]}] is not a supported Job Console implementation."
            }
            components[4].requireEquals("redis-key-loss", "profileId")

            return OwnedRedisNamespace(
                namespace = validatedNamespace,
                deleteUpperBound = deleteUpperBound.requirePositiveNumber("deleteUpperBound"),
                commands = commands,
                maximumScanIterations = maximumScanIterations.requirePositiveNumber("maximumScanIterations"),
            )
        }
    }
}

/**
 * LettuceOwnedRedisKeyCommands 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class LettuceOwnedRedisKeyCommands(
/**
 * commands은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val commands: RedisCommands<String, String>,
) : OwnedRedisKeyCommands {

/**
 * scan은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun scan(
        cursor: String,
        pattern: String,
        count: Long,
    ): OwnedRedisScanPage {
/**
 * page은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val page = commands.scan(
            ScanCursor.of(cursor),
            ScanArgs().match(pattern).limit(count),
        )
        return OwnedRedisScanPage(
            nextCursor = page.cursor,
            keys = page.keys,
        )
    }

/**
 * unlink은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun unlink(keys: List<String>): Long =
        commands.unlink(*keys.toTypedArray())
}
