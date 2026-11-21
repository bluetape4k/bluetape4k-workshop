package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.workshop.commerce.ticket.operations.internal.OperationsService
import io.bluetape4k.workshop.commerce.ticket.operations.internal.ReconciliationJob
import io.bluetape4k.workshop.commerce.ticket.web.TicketEventStream
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import java.io.Serial
import java.io.Serializable
import java.time.Duration
import javax.sql.DataSource

/**
 * concert ticket 예제의 닫힌 configuration contract입니다.
 * @property db 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property redis 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property worker 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property sse 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
@ConfigurationProperties(prefix = "workshop.ticket", ignoreUnknownFields = false)
data class TicketProperties(
    /**
     * `db`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val db: TicketDatabaseProperties = TicketDatabaseProperties(),
    /**
     * `redis`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val redis: TicketRedisProperties = TicketRedisProperties(),
    /**
     * `worker`은 콘서트 티켓 플래시 세일에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val worker: TicketWorkerProperties = TicketWorkerProperties(),
    /**
     * `sse`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sse: TicketSseProperties = TicketSseProperties(),
): Serializable {
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
 * database pool과 workload lane capacity입니다.
 * @property maxPoolSize 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property foregroundPermits 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property workerPermits 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property ssePermits 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property operatorPermits 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property permitTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property transactionTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */
data class TicketDatabaseProperties(
    /**
     * `maxPoolSize`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxPoolSize: Int = 20,
    /**
     * `foregroundPermits`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val foregroundPermits: Int = 12,
    /**
     * `workerPermits`은 콘서트 티켓 플래시 세일에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val workerPermits: Int = 3,
    /**
     * `ssePermits`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val ssePermits: Int = 2,
    /**
     * `operatorPermits`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val operatorPermits: Int = 1,
    /**
     * `permitTimeout`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val permitTimeout: Duration = 250.millis(),
    /**
     * `transactionTimeout`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val transactionTimeout: Duration = 750.millis(),
): Serializable {
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
 * Redis admission lease timing입니다.
 * @property uri 클라이언트 또는 인프라가 접근할 주소입니다. 접근 제어·서명 만료·테넌트 범위를 저장 키와 분리해 적용합니다.
 * @property commandTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property renewInterval 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property leaseTtl 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property rateLimitCapacity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야
 * 합니다.
 * @property rateLimitPeriod 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 */
data class TicketRedisProperties(
    /**
     * `uri`은 콘서트 티켓 플래시 세일에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val uri: String = "redis://localhost:6379",
    /**
     * `commandTimeout`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val commandTimeout: Duration = 500.millis(),
    /**
     * `renewInterval`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val renewInterval: Duration = 2.seconds(),
    /**
     * `leaseTtl`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val leaseTtl: Duration = 5.seconds(),
    /**
     * `rateLimitCapacity`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val rateLimitCapacity: Long = 30,
    /**
     * `rateLimitPeriod`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val rateLimitPeriod: Duration = 1.seconds(),
): Serializable {
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
 * background worker의 실행 한계입니다.
 * @property batchSize 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property runDeadline 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */
data class TicketWorkerProperties(
    /**
     * `batchSize`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val batchSize: Int = 50,
    /**
     * `runDeadline`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val runDeadline: Duration = 10.seconds(),
): Serializable {
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
 * server-sent event capacity입니다.
 * @property queueSize 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property maxConnections 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class TicketSseProperties(
    /**
     * `queueSize`은 콘서트 티켓 플래시 세일에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val queueSize: Int = 32,
    /**
     * `maxConnections`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxConnections: Int = 512,
): Serializable {
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

/** diagnostics에 노출해도 안전한 안정적인 startup failure code입니다. */
/** diagnostics에 노출해도 안전한 안정적인 startup failure code입니다. */
enum class TicketStartupFailure {
    /**
     * `TicketStartupFailure.INVALID_DATABASE_CAPACITY`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_DATABASE_CAPACITY,
    /**
     * `TicketStartupFailure.INVALID_DATABASE_TIMING`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_DATABASE_TIMING,
    /**
     * `TicketStartupFailure.INVALID_REDIS_CONFIGURATION`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_REDIS_CONFIGURATION,
    /**
     * `TicketStartupFailure.INVALID_REDIS_LEASE_TIMING`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_REDIS_LEASE_TIMING,
    /**
     * `TicketStartupFailure.INVALID_WORKER_CAPACITY`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_WORKER_CAPACITY,
    /**
     * `TicketStartupFailure.INVALID_SSE_CAPACITY`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_SSE_CAPACITY,
}

/**
 * raw configuration value를 누출하지 않고 startup을 실패시킵니다.
 * @property code 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
class TicketStartupException(
    /**
     * `code`은 콘서트 티켓 플래시 세일에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: TicketStartupFailure,
): IllegalStateException(code.name) {
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

/** field 간 capacity와 timing invariant를 검증합니다. */
/** field 간 capacity와 timing invariant를 검증합니다. */
object TicketStartupValidator {
    /**
     * 콘서트 티켓 판매 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private const val RESERVED_DATABASE_CONNECTIONS = 2

    /**
     * validate은 콘서트 티켓 판매 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun validate(properties: TicketProperties) {
        validateDatabase(properties.db)
        validateRedis(properties.redis)
        validateWorker(properties.worker)
        validateSse(properties.sse)
    }

    /**
     * validateDatabase은 콘서트 티켓 판매 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param db 업무 처리에 필요한 db 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validateDatabase(db: TicketDatabaseProperties) {
        val lanes = listOf(db.foregroundPermits, db.workerPermits, db.ssePermits, db.operatorPermits)
        if (db.maxPoolSize <= RESERVED_DATABASE_CONNECTIONS || lanes.any { it <= 0 } ||
            lanes.sum() > db.maxPoolSize - RESERVED_DATABASE_CONNECTIONS
        ) {
            throw TicketStartupException(TicketStartupFailure.INVALID_DATABASE_CAPACITY)
        }
        if (!db.permitTimeout.isPositive || !db.transactionTimeout.isPositive) {
            throw TicketStartupException(TicketStartupFailure.INVALID_DATABASE_TIMING)
        }
    }

    /**
     * validateRedis은 콘서트 티켓 판매 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param redis 업무 처리에 필요한 redis 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validateRedis(redis: TicketRedisProperties) {
        if (redis.uri.isBlank() || redis.rateLimitCapacity <= 0 || !redis.rateLimitPeriod.isPositive()) {
            throw TicketStartupException(TicketStartupFailure.INVALID_REDIS_CONFIGURATION)
        }
        if (!redis.commandTimeout.isPositive() || !redis.renewInterval.isPositive() || !redis.leaseTtl.isPositive() ||
            redis.commandTimeout >= redis.renewInterval ||
            redis.renewInterval.multipliedBy(2) >= redis.leaseTtl
        ) {
            throw TicketStartupException(TicketStartupFailure.INVALID_REDIS_LEASE_TIMING)
        }
    }

    /**
     * validateWorker은 콘서트 티켓 판매 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param worker 업무 처리에 필요한 worker 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validateWorker(worker: TicketWorkerProperties) {
        if (worker.batchSize <= 0 || !worker.runDeadline.isPositive()) {
            throw TicketStartupException(TicketStartupFailure.INVALID_WORKER_CAPACITY)
        }
    }

    /**
     * validateSse은 콘서트 티켓 판매 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param sse 업무 처리에 필요한 sse 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validateSse(sse: TicketSseProperties) {
        if (sse.queueSize <= 0 || sse.maxConnections <= 0) {
            throw TicketStartupException(TicketStartupFailure.INVALID_SSE_CAPACITY)
        }
    }
}

/** startup validation gate를 설치합니다. */
/** startup validation gate를 설치합니다. */
@Configuration(proxyBeanMethods = false)
internal class TicketConfiguration {
    /**
     * ticketStartupValidation은 콘서트 티켓 판매 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun ticketStartupValidation(properties: TicketProperties): SmartInitializingSingleton =
        SmartInitializingSingleton { TicketStartupValidator.validate(properties) }

    /**
     * ticketMigrationReadiness은 콘서트 티켓 판매의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun ticketMigrationReadiness(): TicketMigrationReadiness = TicketMigrationReadiness()

    /**
     * ticketMigrationRunner은 콘서트 티켓 판매 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param dataSource 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param readiness 업무 처리에 필요한 readiness 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun ticketMigrationRunner(
        dataSource: DataSource,
        readiness: TicketMigrationReadiness,
    ): TicketMigrationRunner =
        TicketMigrationRunner(
            dataSource = dataSource,
            migration =
                TicketMigration(
                    version = "001",
                    resource = ClassPathResource("db/migration/V001__concert_ticket_flash_sale.sql"),
                ),
            advisoryLockKey = TICKET_MIGRATION_LOCK_KEY,
            readiness = readiness,
        )

    /**
     * ticketSchemaMigration은 콘서트 티켓 판매 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param runner 업무 처리에 필요한 runner 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun ticketSchemaMigration(runner: TicketMigrationRunner): SmartInitializingSingleton =
        SmartInitializingSingleton { runner.migrate() }

    /**
     * ticketEventStream은 콘서트 티켓 판매 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun ticketEventStream(properties: TicketProperties): TicketEventStream =
        TicketEventStream(properties.sse.queueSize, properties.sse.maxConnections)

    /**
     * ticketLifecycle은 콘서트 티켓 판매 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventStream 콘서트 티켓 판매 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    @Bean
    fun ticketLifecycle(eventStream: TicketEventStream): TicketLifecycle = TicketLifecycle(eventStream)

    /**
     * ticketMetrics은 콘서트 티켓 판매 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param registry 업무 처리에 필요한 registry 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun ticketMetrics(registry: MeterRegistry): TicketMetrics = TicketMetrics(registry)

    /**
     * ticketMigrationHealth은 콘서트 티켓 판매의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param readiness 업무 처리에 필요한 readiness 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun ticketMigrationHealth(readiness: TicketMigrationReadiness): TicketMigrationHealthIndicator =
        TicketMigrationHealthIndicator(readiness)

    /**
     * ticketRedisHealth은 콘서트 티켓 판매의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param resources 업무 처리에 필요한 resources 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun ticketRedisHealth(resources: TicketRedisResources): TicketRedisHealthIndicator =
        TicketRedisHealthIndicator { resources.commands().ping() }

    /**
     * ticketLivenessHealth은 콘서트 티켓 판매의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun ticketLivenessHealth(): TicketLivenessHealthIndicator = TicketLivenessHealthIndicator()

    /**
     * ticketOperations은 콘서트 티켓 판매 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param jobs 업무 처리에 필요한 jobs 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun ticketOperations(
        jobs: ObjectProvider<ReconciliationJob>,
        properties: TicketProperties,
    ): OperationsService = OperationsService(
        jobs = jobs.orderedStream().toList(),
        operatorPermits = properties.db.operatorPermits,
        maxBatchSize = properties.worker.batchSize,
        runDeadline = properties.worker.runDeadline,
    )

    companion object {
        /**
         * `TICKET_MIGRATION_LOCK_KEY`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private const val TICKET_MIGRATION_LOCK_KEY: Long = 521_001L
    }
}
