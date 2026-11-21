package io.bluetape4k.workshop.commerce.ticket.redis

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.script.RedisScript
import io.bluetape4k.redis.lettuce.script.RedisScriptRunner
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.lettuce.core.RedisException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import java.io.Serial
import java.io.Serializable
import java.time.Duration

/**
 * 하나의 Redis Cluster hash slot을 공유해야 하는 두 Redis key입니다.
 * @property ip 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property user 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class LeaseKeys(
    /**
     * `ip`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val ip: String,
    /**
     * `user`은 콘서트 티켓 플래시 세일에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val user: String,
): Serializable {

    init {
        require(ip != user) { "ip[$ip] must not equal to user[$user]" }
        require(hashTag(ip) == hashTag(user)) { "ip[$ip] hashTag must be equal to user[$user] hashTag" }
    }

    /**
     * asArray은 콘서트 티켓 판매 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun asArray(): Array<String> = arrayOf(ip, user)

    companion object {
        @Serial
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * `HASH_TAG`은 콘서트 티켓 플래시 세일에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
         *
         * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
         */
        private val HASH_TAG = Regex("\\{([^{}]+)}")

        /**
         * hashTag은 콘서트 티켓 판매의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param key 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
         */
        private fun hashTag(key: String): String =
            (HASH_TAG.find(key)?.groupValues?.get(1)).requireNotNull {
                "lease key must contain a non-empty Redis hash tag"
            }
    }
}

/**
 * opaque하고 versioned된 PRF output입니다. token은 raw identity나 request key를 포함하면 안 됩니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property token 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */
data class LeaseOwner(
    /**
     * `version`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: Int,
    /**
     * `token`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val token: String,
): Serializable {
    init {
        version.requirePositiveNumber("version")
        token.length.requireEquals(TOKEN_LENGTH, "token.length")
        require(token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "token[$token] only have a letter or digit or `-` or `_`" }
    }
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     * wireValue은 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val wireValue: String = "v$version:$token"

    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         * TOKEN_LENGTH은 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */
        private const val TOKEN_LENGTH = 43
    }
}

/**
 * 현재 owner를 먼저 두고, response-loss recovery를 위해 보존한 read-key owner를 뒤에 둡니다.
 * @property keys 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property ownerCandidates 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property ttl 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */
data class LeaseRequest(
    /**
     * `keys`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val keys: LeaseKeys,
    /**
     * `ownerCandidates`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val ownerCandidates: List<LeaseOwner>,
    /**
     * `ttl`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val ttl: Duration,
): Serializable {
    init {
        ownerCandidates.requireNotEmpty("ownerCandidates")
        ownerCandidates.map { it.version }.distinct().size
            .requireEquals(ownerCandidates.size, "ownerCandidates.distinctVersions")
        ttl.requirePositiveDuration("ttl")
    }
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     * currentOwner은 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    val currentOwner: LeaseOwner get() = ownerCandidates.first()

    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * LeaseDecision은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
sealed interface LeaseDecision: Serializable {
/**
 * Acquired은 콘서트 티켓 판매에서 전달되는 불변 데이터 모델입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */

    data class Acquired(val version: Int): LeaseDecision {
        companion object {
            /**
             * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }
/**
 * AlreadyOwned은 콘서트 티켓 판매에서 전달되는 불변 데이터 모델입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


    data class AlreadyOwned(val version: Int): LeaseDecision {
        companion object {
            /**
             * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * Busy은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object Busy: LeaseDecision {
        /**
         * readResolve은 콘서트 티켓 판매의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
         * 않습니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        private fun readResolve(): Any = Busy
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */

        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** #1065에서 추적하는 재사용 가능한 bluetape4k-lettuce feature로 나중에 교체할 수 있는 좁은 seam입니다. */
/** #1065에서 추적하는 재사용 가능한 bluetape4k-lettuce feature로 나중에 교체할 수 있는 좁은 seam입니다. */
fun interface MultiKeyLeasePort {
    /**
     * acquire은 콘서트 티켓 판매의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 콘서트 티켓 판매 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun acquire(request: LeaseRequest): LeaseDecision
}

/**
 * atomic Redis Lua script로 구현한 application-owned two-key lease입니다.
 * @property commandsProvider 콘서트 티켓 판매 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */
class MultiKeyLeaseAdapter(
    /**
     * `commandsProvider`은 콘서트 티켓 플래시 세일에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commandsProvider: () -> RedisCommands<String, String>,
): MultiKeyLeasePort {

    constructor(commands: RedisCommands<String, String>): this({ commands })

    /**
     * acquire은 콘서트 티켓 판매의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 콘서트 티켓 판매 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    override fun acquire(request: LeaseRequest): LeaseDecision {
        val response = RedisScriptRunner.run<String>(
            commands = commandsProvider(),
            script = ACQUIRE,
            outputType = ScriptOutputType.VALUE,
            keys = request.keys.asArray(),
            request.currentOwner.wireValue,
            request.ttl.toMillis().toString(),
            *request.ownerCandidates.map { it.wireValue }.toTypedArray(),
        )
        if (response == BUSY) return LeaseDecision.Busy

        val (status, wireOwner) = response.split(RESPONSE_SEPARATOR, limit = 2).requirePair()
        val owner = request.ownerCandidates.singleOrNull { it.wireValue == wireOwner }
            ?: throw RedisException("Redis returned an unknown lease owner")

        return when (status) {
            ACQUIRED -> LeaseDecision.Acquired(owner.version)
            OWNED -> LeaseDecision.AlreadyOwned(owner.version)
            else  -> throw RedisException("Redis returned an unknown lease status")
        }.also {
            log.debug { "acquire: $it for request: $request" }
        }
    }

    /**
     * renew은 콘서트 티켓 판매 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 콘서트 티켓 판매 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun renew(request: LeaseRequest): Boolean =
        runIntegerScript(RENEW, request) == 1L

    /**
     * release은 콘서트 티켓 판매의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 콘서트 티켓 판매 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun release(request: LeaseRequest): Boolean =
        runIntegerScript(RELEASE, request) == 1L

    /**
     * runIntegerScript은 콘서트 티켓 판매 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param script 업무 처리에 필요한 script 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param request 콘서트 티켓 판매 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun runIntegerScript(
        script: RedisScript,
        request: LeaseRequest,
    ): Long =
        RedisScriptRunner.run(
            commands = commandsProvider(),
            script = script,
            outputType = ScriptOutputType.INTEGER,
            keys = request.keys.asArray(),
            request.ttl.toMillis().toString(),
            *request.ownerCandidates.map { it.wireValue }.toTypedArray(),
        )

    /**
     * requirePair은 콘서트 티켓 판매 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun List<String>.requirePair(): Pair<String, String> {
        if (size != 2) throw RedisException("Redis returned a malformed lease response")
        return this[0] to this[1]
    }

    companion object: KLogging() {
        /**
         * `BUSY`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val BUSY = "B"
        /**
         * `ACQUIRED`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val ACQUIRED = "A"
        /**
         * `OWNED`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val OWNED = "O"
        /**
         * `RESPONSE_SEPARATOR`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private const val RESPONSE_SEPARATOR = '|'

        /**
         * `ACQUIRE`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val ACQUIRE =
            RedisScript(
                """
                local selected = nil
                for _, key in ipairs(KEYS) do
                  local value = redis.call('GET', key)
                  if value then
                    if selected and selected ~= value then return 'B' end
                    selected = value
                  end
                end

                local status = 'O'
                if not selected then
                  selected = ARGV[1]
                  status = 'A'
                else
                  local retained = false
                  for index = 3, #ARGV do
                    if ARGV[index] == selected then retained = true break end
                  end
                  if not retained then return 'B' end
                end

                for _, key in ipairs(KEYS) do
                  redis.call('SET', key, selected, 'PX', ARGV[2])
                end
                return status .. '|' .. selected
                """.trimIndent(),
            )

        /**
         * `RENEW`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val RENEW =
            RedisScript(
                """
                local selected = nil
                for _, key in ipairs(KEYS) do
                  local value = redis.call('GET', key)
                  if not value then return 0 end
                  if selected and selected ~= value then return 0 end
                  selected = value
                end
                local owned = false
                for index = 2, #ARGV do
                  if ARGV[index] == selected then owned = true break end
                end
                if not owned then return 0 end
                for _, key in ipairs(KEYS) do redis.call('PEXPIRE', key, ARGV[1]) end
                return 1
                """.trimIndent(),
            )

        /**
         * `RELEASE`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        private val RELEASE =
            RedisScript(
                """
                local selected = nil
                for _, key in ipairs(KEYS) do
                  local value = redis.call('GET', key)
                  if value then
                    if selected and selected ~= value then return 0 end
                    selected = value
                  end
                end
                if not selected then return 0 end
                local owned = false
                for index = 2, #ARGV do
                  if ARGV[index] == selected then owned = true break end
                end
                if not owned then return 0 end
                redis.call('DEL', unpack(KEYS))
                return 1
                """.trimIndent(),
            )
    }
}

/** new-purchase admission error는 안정적이며 Redis 세부사항과 의도적으로 분리합니다. */
/** new-purchase admission error는 안정적이며 Redis 세부사항과 의도적으로 분리합니다. */
class AdmissionTemporarilyUnavailable(cause: Throwable):
    IllegalStateException("admission_temporarily_unavailable", cause) {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * PurchaseApprovalInProgress은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class PurchaseApprovalInProgress: IllegalStateException("purchase_approval_in_progress") {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 새 foreground purchase admission에만 fail-closed 동작을 적용합니다.
 * @property lease 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */
class ForegroundLeaseGate(
    /**
     * `lease`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val lease: MultiKeyLeasePort,
) {
    companion object: KLogging()

    /**
     * acquire은 콘서트 티켓 판매의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 콘서트 티켓 판매 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun acquire(request: LeaseRequest): LeaseDecision {
        return try {
            val decision = lease.acquire(request)
            if (decision == LeaseDecision.Busy) {
                throw PurchaseApprovalInProgress()
            } else {
                decision
            }
        } catch (failure: RedisException) {
            log.warn(failure) { "foreground_lease_unavailable" }
            throw AdmissionTemporarilyUnavailable(failure)
        }
    }
}
