package io.bluetape4k.workshop.shared.redis

import java.util.TreeSet

/**
 * Redis SCAN 결과를 client 구현에서 분리한 공통 test fixture 페이지다.
 */
data class OwnedRedisScanPage(
    val nextCursor: String,
    val keys: List<String>,
)

/**
 * namespace 정리 알고리즘이 요구하는 최소 Redis 명령 계약이다.
 *
 * Lettuce나 다른 Redis client는 소비자 모듈의 adapter에서 이 계약으로 변환한다.
 */
interface OwnedRedisKeyCommands {

    fun scan(
        cursor: String,
        pattern: String,
        count: Long,
    ): OwnedRedisScanPage

    fun unlink(keys: List<String>): Long
}

/**
 * 정리 중 writer를 멈추는 경계를 주입한다. Redis client와 도메인 lock 구현은 포함하지 않는다.
 */
fun interface OwnedRedisWriterBarrier {

    fun pause(): AutoCloseable

    companion object {
        val NONE: OwnedRedisWriterBarrier = OwnedRedisWriterBarrier { AutoCloseable {} }
    }
}

/**
 * namespace 정리에서 실제로 제거된 key 목록이다.
 */
data class OwnedRedisDeletionResult(
    val deletedKeys: List<String>,
)

/**
 * 고유 실행 namespace만 bounded scan/unlink하는 domain-independent test fixture다.
 */
class OwnedRedisNamespace private constructor(
    private val namespace: String,
    private val deleteUpperBound: Int,
    private val commands: OwnedRedisKeyCommands,
    private val maximumScanIterations: Int,
    private val implementationPolicy: (String) -> Boolean,
) {

    /**
     * key가 현재 실행 namespace의 두 owner component를 포함하는지 판정한다.
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
     * writer를 멈춘 동안 후보를 검증하고, bounded UNLINK 후 namespace 수렴을 확인한다.
     */
    fun deleteOwnedKeys(
        writerBarrier: OwnedRedisWriterBarrier,
    ): OwnedRedisDeletionResult = writerBarrier.pause().use {
        val candidates = scanAll()
        check(candidates.all(::owns)) {
            "Redis SCAN returned a key outside the owned namespace."
        }
        check(candidates.size <= deleteUpperBound) {
            "Owned Redis key count[${candidates.size}] exceeds delete upper bound[$deleteUpperBound]."
        }

        if (candidates.isNotEmpty()) {
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
        private const val INITIAL_CURSOR = "0"
        private const val NAMESPACE_COMPONENT_COUNT = 5
        private const val OWNED_KEY_COMPONENT_COUNT = 2
        private val OWNER_COMPONENT_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        /**
         * domain-specific implementation policy를 주입해 namespace를 생성한다.
         */
        fun parse(
            namespace: String,
            deleteUpperBound: Int,
            commands: OwnedRedisKeyCommands,
            maximumScanIterations: Int = 1_024,
            implementationPolicy: (String) -> Boolean,
        ): OwnedRedisNamespace {
            require(namespace.isNotBlank()) { "namespace must not be blank." }
            require(namespace.endsWith(':')) {
                "namespace[$namespace] must end with a terminal delimiter."
            }
            val components = namespace.dropLast(1).split(':')
            require(components.size == NAMESPACE_COMPONENT_COUNT) {
                "namespace[$namespace] must contain $NAMESPACE_COMPONENT_COUNT components."
            }
            require(components[0] == "hc") { "namespaceRoot must be hc." }
            require(components[1] == "v1") { "namespaceVersion must be v1." }
            require(OWNER_COMPONENT_PATTERN.matches(components[2])) {
                "runId[${components[2]}] must be a valid namespace component."
            }
            require(implementationPolicy(components[3])) {
                "implementation[${components[3]}] is not supported by this consumer."
            }
            require(components[4] == "redis-key-loss") {
                "profileId must be redis-key-loss."
            }
            require(deleteUpperBound > 0) { "deleteUpperBound must be positive." }
            require(maximumScanIterations > 0) { "maximumScanIterations must be positive." }

            return OwnedRedisNamespace(
                namespace = namespace,
                deleteUpperBound = deleteUpperBound,
                commands = commands,
                maximumScanIterations = maximumScanIterations,
                implementationPolicy = implementationPolicy,
            )
        }
    }
}
