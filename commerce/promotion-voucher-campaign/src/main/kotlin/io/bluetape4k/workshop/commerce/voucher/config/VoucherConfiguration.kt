package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeKeyRing
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import io.bluetape4k.workshop.commerce.voucher.web.VoucherHttpProperties
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.SpringProperties
import org.springframework.core.env.Environment
import org.springframework.core.env.getProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.time.toKotlinDuration

@ConfigurationProperties(prefix = "workshop.voucher", ignoreUnknownFields = false)
/**
 * VoucherProperties은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherProperties(
    /**
     * `db`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val db: VoucherDatabaseProperties = VoucherDatabaseProperties(),
    /**
     * `keys`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val keys: VoucherKeyProperties = VoucherKeyProperties(),
    /**
     * `redis`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val redis: VoucherRedisProperties = VoucherRedisProperties(),
    /**
     * `worker`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val worker: VoucherWorkerProperties = VoucherWorkerProperties(),
    /**
     * `sse`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sse: VoucherSseProperties = VoucherSseProperties(),
    /**
     * `retention`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retention: VoucherRetentionProperties = VoucherRetentionProperties(),
    /**
     * `http`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val http: VoucherHttpProperties = VoucherHttpProperties(),
)

/**
 * VoucherDatabaseProperties은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherDatabaseProperties(
    /**
     * `foregroundPermits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val foregroundPermits: Int = 12,
    /**
     * `backgroundPermits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val backgroundPermits: Int = 4,
    /**
     * `permitTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val permitTimeout: Duration = 250.millis(),
    /**
     * `lockTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val lockTimeout: Duration = 5.seconds(),
)

/**
 * VoucherKeyProperties은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherKeyProperties(
    /**
     * `currentVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val currentVersion: Int = 1,
    /**
     * `activeReadVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val activeReadVersions: Set<Int> = setOf(1),
    /**
     * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Map<Int, String> = emptyMap(),
    /**
     * `verification`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val verification: Map<Int, String> = emptyMap(),
    /**
     * `identity`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val identity: String = "",
    /**
     * `risk`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val risk: String = "",
    /**
     * `redisSlot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val redisSlot: String = "",
)

/**
 * VoucherRedisProperties은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherRedisProperties(
    /**
     * `enabled`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val enabled: Boolean = false,
    /**
     * `uri`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val uri: String = "redis://127.0.0.1:6379",
    /**
     * `commandTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val commandTimeout: Duration = 500.millis(),
    /**
     * `failureThreshold`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val failureThreshold: Int = 3,
    /**
     * `recoverySuccessThreshold`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val recoverySuccessThreshold: Int = 3,
    /**
     * `probeInterval`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val probeInterval: Duration = 1.seconds(),
    /**
     * `maxInFlightProbes`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxInFlightProbes: Int = 1,
    /**
     * `quotaCapacity`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val quotaCapacity: Long = 10,
    /**
     * `quotaPeriod`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val quotaPeriod: Duration = 1.minutes(),
    /**
     * `bloomExpectedInsertions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val bloomExpectedInsertions: Long = 100_000,
    /**
     * `bloomFalseProbability`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val bloomFalseProbability: Double = 0.01,
)

/**
 * VoucherWorkerProperties은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherWorkerProperties(
    /**
     * `batchSize`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val batchSize: Int = 50,
    /**
     * `runDeadline`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val runDeadline: Duration = 10.seconds(),
    /**
     * `transactionTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val transactionTimeout: Duration = 5.seconds(),
    /**
     * `maxAttempts`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val maxAttempts: Int = 5,
    /**
     * `instanceId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val instanceId: String = "voucher-node",
    /**
     * `schedulingEnabled`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val schedulingEnabled: Boolean = true,
    /**
     * `interval`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val interval: Duration = 5.seconds(),
    /**
     * `initialDelay`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val initialDelay: Duration = 5.seconds(),
)

/**
 * VoucherSseProperties은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherSseProperties(
    /**
     * `maxCampaigns`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxCampaigns: Int = 32,
    /**
     * `queueSize`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val queueSize: Int = 32,
    /**
     * `pollInterval`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val pollInterval: Duration = 500.millis(),
    /**
     * `maxIdleInterval`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val maxIdleInterval: Duration = 2.seconds(),
    /**
     * `heartbeatInterval`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val heartbeatInterval: Duration = 15.seconds(),
    /**
     * `writeTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val writeTimeout: Duration = 5.seconds(),
    /**
     * `maxRows`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val maxRows: Int = 200,
    /**
     * `maxPayloadBytes`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxPayloadBytes: Int = 256 * 1024,
)

/**
 * VoucherRuntimeEnvironment은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherRuntimeEnvironment(
    /**
     * `activeProfiles`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val activeProfiles: Set<String>,
    /**
     * `serverAddress`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val serverAddress: String,
    /**
     * `hikariMaximumPoolSize`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val hikariMaximumPoolSize: Int,
)

/**
 * ReferencedKeyVersions은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class ReferencedKeyVersions(
    /**
     * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Set<Int> = emptySet(),
    /**
     * `verification`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val verification: Set<Int> = emptySet(),
)

/**
 * ReferencedKeyVersionSource은 프로모션 바우처 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface ReferencedKeyVersionSource {
    /**
     * referencedVersions은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun referencedVersions(): ReferencedKeyVersions

    companion object {
        val NONE: ReferencedKeyVersionSource = ReferencedKeyVersionSource { ReferencedKeyVersions() }
    }
}

/**
 * StartupFailureCode은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class StartupFailureCode {
    /**
     * `StartupFailureCode.UNKNOWN_PROPERTY`은 StartupFailureCode 업무 상태의 `UNKNOWN_PROPERTY` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    UNKNOWN_PROPERTY,
    /**
     * `StartupFailureCode.INVALID_RANGE`은 StartupFailureCode 업무 상태의 `INVALID_RANGE` 단계입니다. 허용된 상태
     * 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    INVALID_RANGE,
    /**
     * `StartupFailureCode.MISSING_KEY`은 StartupFailureCode 업무 상태의 `MISSING_KEY` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    MISSING_KEY,
    /**
     * `StartupFailureCode.WEAK_KEY`은 StartupFailureCode 업무 상태의 `WEAK_KEY` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    WEAK_KEY,
    /**
     * `StartupFailureCode.TEST_KEY_FORBIDDEN`은 StartupFailureCode 업무 상태의 `TEST_KEY_FORBIDDEN`
     * 단계입니다. 허용된
     * 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    TEST_KEY_FORBIDDEN,
    /**
     * `StartupFailureCode.INVALID_KEY_RING`은 StartupFailureCode 업무 상태의 `INVALID_KEY_RING` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    INVALID_KEY_RING,
    /**
     * `StartupFailureCode.DOMAIN_KEY_REUSE`은 StartupFailureCode 업무 상태의 `DOMAIN_KEY_REUSE` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    DOMAIN_KEY_REUSE,
    /**
     * `StartupFailureCode.REFERENCED_KEY_MISSING`은 StartupFailureCode 업무 상태의
     * `REFERENCED_KEY_MISSING`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    REFERENCED_KEY_MISSING,
    /**
     * `StartupFailureCode.PUBLIC_DEMO_BIND`은 StartupFailureCode 업무 상태의 `PUBLIC_DEMO_BIND` 단계입니다.
     * 허용된 상태
     * 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    PUBLIC_DEMO_BIND,
    /**
     * `StartupFailureCode.UNEXPECTED_STARTUP_FAILURE`은 StartupFailureCode 업무 상태의
     * `UNEXPECTED_STARTUP_FAILURE` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    UNEXPECTED_STARTUP_FAILURE,
}

/**
 * VoucherStartupException은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherStartupException(
    /**
     * `code`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val code: StartupFailureCode,
): IllegalStateException(code.name)

/**
 * sanitizedStartupCode은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 * @param failure 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
 */
internal fun sanitizedStartupCode(failure: Throwable): StartupFailureCode {
    var current: Throwable? = failure
    while (current != null) {
        if (current is VoucherStartupException) return current.code
        val message = current.message.orEmpty().lowercase()
        if ("left unbound" in message || "unbound configuration" in message) {
            return StartupFailureCode.UNKNOWN_PROPERTY
        }
        current = current.cause
    }
    return StartupFailureCode.UNEXPECTED_STARTUP_FAILURE
}

/** exception에 secret value를 노출하지 않고 fail-closed production check를 적용합니다. */
/** exception에 secret value를 노출하지 않고 fail-closed production check를 적용합니다. */
internal class VoucherStartupValidator(
    /**
     * `referencedKeyVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val referencedKeyVersions: ReferencedKeyVersionSource = ReferencedKeyVersionSource.NONE,
) {

    companion object: KLogging() {
        /**
         * `HIKARI_MAXIMUM_POOL_SIZE`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private const val HIKARI_MAXIMUM_POOL_SIZE = 16
        /**
         * `BACKGROUND_PERMITS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val BACKGROUND_PERMITS = 4
        /**
         * `MINIMUM_KEY_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private const val MINIMUM_KEY_BYTES = 32
        /**
         * `DEMO_PROFILES`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
         *
         * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
         */
        private val DEMO_PROFILES = setOf("demo", "test", "local")
    }

    /**
     * validate은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun validate(
        properties: VoucherProperties,
        runtime: VoucherRuntimeEnvironment,
    ) {
        validateDatabase(properties.db, runtime.hikariMaximumPoolSize)
        validateRedis(properties.redis)
        validateWorker(properties.worker)
        validateSse(properties.sse)
        validateRetention(properties.retention)
        validateHttp(properties.http)
        validateKeys(properties.keys, runtime.isProduction())
        validateReferencedKeys(properties.keys)
        validateBind(runtime)
    }

    /**
     * validateDatabase은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun validateDatabase(
        database: VoucherDatabaseProperties,
        hikariMaximumPoolSize: Int,
    ) {
        val invalid = hikariMaximumPoolSize != HIKARI_MAXIMUM_POOL_SIZE ||
                database.foregroundPermits <= 0 ||
                database.backgroundPermits != BACKGROUND_PERMITS ||
                database.foregroundPermits + database.backgroundPermits > hikariMaximumPoolSize ||
                database.permitTimeout.isNegative ||
                database.permitTimeout.isZero ||
                database.lockTimeout.isNegative ||
                database.lockTimeout.isZero

        if (invalid) fail(StartupFailureCode.INVALID_RANGE)
    }

    /**
     * validateKeys은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun validateKeys(
        keys: VoucherKeyProperties,
        production: Boolean,
    ) {
        val currentGeneration = keys.generation[keys.currentVersion]
        val currentVerification = keys.verification[keys.currentVersion]
        val required = listOf(currentGeneration, currentVerification, keys.identity, keys.risk, keys.redisSlot)
        if (required.any { it.isNullOrBlank() }) {
            fail(StartupFailureCode.MISSING_KEY)
        }

        val nonNullRequired = required.filterNotNull()
        if (nonNullRequired.any { it.toUtf8Bytes().size < MINIMUM_KEY_BYTES }) {
            fail(StartupFailureCode.WEAK_KEY)
        }
        if (production && nonNullRequired.any(::isKnownTestKey)) {
            fail(StartupFailureCode.TEST_KEY_FORBIDDEN)
        }
        val ringInvalid = keys.currentVersion !in keys.activeReadVersions ||
                keys.activeReadVersions.any { it !in keys.generation || it !in keys.verification }
        if (ringInvalid) {
            fail(StartupFailureCode.INVALID_KEY_RING)
        }

        val domains = buildList {
            keys.generation.values.forEach { add("generation" to it) }
            keys.verification.values.forEach { add("verification" to it) }
            add("identity" to keys.identity)
            add("risk" to keys.risk)
            add("redis-slot" to keys.redisSlot)
        }
        val reusedAcrossDomains = domains
            .groupBy { it.second }
            .values
            .any { values ->
                values.map { it.first }.distinct().size > 1
            }
        if (reusedAcrossDomains) {
            fail(StartupFailureCode.DOMAIN_KEY_REUSE)
        }
    }

    /**
     * validateRedis은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param redis 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validateRedis(redis: VoucherRedisProperties) {
        val invalid = (redis.enabled && redis.uri.isBlank()) ||
                redis.commandTimeout.isNegative ||
                redis.commandTimeout.isZero ||
                redis.failureThreshold <= 0 ||
                redis.recoverySuccessThreshold <= 0 ||
                redis.probeInterval.isNegative ||
                redis.probeInterval.isZero ||
                redis.maxInFlightProbes <= 0 ||
                redis.quotaCapacity <= 0 ||
                redis.quotaPeriod.isNegative ||
                redis.quotaPeriod.isZero ||
                redis.bloomExpectedInsertions <= 0 ||
                redis.bloomFalseProbability <= 0.0 ||
                redis.bloomFalseProbability >= 1.0

        if (invalid) fail(StartupFailureCode.INVALID_RANGE)
    }

    /**
     * validateWorker은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param worker 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validateWorker(worker: VoucherWorkerProperties) {
        val invalid = worker.batchSize !in 1..50 ||
                worker.runDeadline.isNegative ||
                worker.runDeadline.isZero ||
                worker.transactionTimeout.isNegative ||
                worker.transactionTimeout.isZero ||
                worker.transactionTimeout > worker.runDeadline ||
                worker.maxAttempts !in 1..20 ||
                worker.instanceId.isBlank() ||
                worker.instanceId.length > 128 ||
                worker.interval.isNegative ||
                worker.interval.isZero ||
                worker.initialDelay.isNegative

        if (invalid) fail(StartupFailureCode.INVALID_RANGE)
    }

    /**
     * validateSse은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param sse 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validateSse(sse: VoucherSseProperties) {
        val invalid = sse.maxCampaigns !in 1..32 ||
                sse.queueSize !in 1..32 ||
                sse.pollInterval.isNegative ||
                sse.pollInterval.isZero ||
                sse.maxIdleInterval < sse.pollInterval ||
                sse.maxIdleInterval > 2.seconds() ||
                sse.heartbeatInterval.isNegative ||
                sse.heartbeatInterval.isZero ||
                sse.writeTimeout.isNegative ||
                sse.writeTimeout.isZero ||
                sse.maxRows !in 1..200 ||
                sse.maxPayloadBytes !in 1..(256 * 1024)

        if (invalid) fail(StartupFailureCode.INVALID_RANGE)
    }

    /**
     * validateHttp은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param http 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validateHttp(http: VoucherHttpProperties) {
        val invalid = http.maxHeaderLength !in 8..256 ||
                http.maxScalarBytes !in 32..1024 ||
                http.maxPageSize !in 1..100 ||
                http.maxCursorBytes !in 64..512 ||
                http.operatorSecret.toUtf8Bytes().size < MINIMUM_KEY_BYTES ||
                http.operatorGuard.length !in 8..64 ||
                http.allowedHosts.isEmpty()

        if (invalid) fail(StartupFailureCode.INVALID_RANGE)
    }

    /**
     * validateRetention은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param retention 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validateRetention(retention: VoucherRetentionProperties) {
        try {
            VoucherRetentionPolicy(retention)
        } catch (_: IllegalArgumentException) {
            fail(StartupFailureCode.INVALID_RANGE)
        }
    }

    /**
     * validateReferencedKeys은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param keys 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validateReferencedKeys(keys: VoucherKeyProperties) {
        val referenced = referencedKeyVersions.referencedVersions()
        if (!keys.generation.keys.containsAll(referenced.generation) ||
            !keys.verification.keys.containsAll(referenced.verification)
        ) {
            fail(StartupFailureCode.REFERENCED_KEY_MISSING)
        }
    }

    /**
     * validateBind은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param runtime 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validateBind(runtime: VoucherRuntimeEnvironment) {
        if (runtime.isProduction() && runtime.activeProfiles.any { it in DEMO_PROFILES } && !runtime.isLoopback()) {
            fail(StartupFailureCode.PUBLIC_DEMO_BIND)
        }
    }

/**
 * `isProduction`은 프로모션 바우처 운영 환경의 바인딩 안전성을 확인하는 내부 단계입니다.
 *
 * 운영 프로파일이 활성화된 상태에서 외부에 노출되는 주소로 잘못 바인딩하지 않도록 시작 시점의 안전 경계를 유지합니다.
 */
    private fun VoucherRuntimeEnvironment.isProduction(): Boolean =
        activeProfiles.any { it == "prod" || it == "production" }

/**
 * `isLoopback`은 프로모션 바우처 운영 환경의 바인딩 안전성을 확인하는 내부 단계입니다.
 *
 * 운영 프로파일이 활성화된 상태에서 외부에 노출되는 주소로 잘못 바인딩하지 않도록 시작 시점의 안전 경계를 유지합니다.
 */
    private fun VoucherRuntimeEnvironment.isLoopback(): Boolean =
        serverAddress == "127.0.0.1" || serverAddress == "::1" || serverAddress == "localhost"

    /**
     * isKnownTestKey은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param value 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun isKnownTestKey(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.startsWith("test-") ||
                normalized.startsWith("local-") ||
                "known-test" in normalized
    }

    /**
     * fail은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param code 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun fail(code: StartupFailureCode): Nothing =
        throw VoucherStartupException(code)
}

/** Java 25 executor, Exposed transaction boundary, bounded JDBC lane을 제공합니다. */
/** Java 25 executor, Exposed transaction boundary, bounded JDBC lane을 제공합니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherProperties::class)
internal class VoucherConfiguration {

    companion object: KLogging() {
        /**
         * `SPRING_HTTP_RESPONSE_FLUSH_ENABLED`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val SPRING_HTTP_RESPONSE_FLUSH_ENABLED = "spring.http.response.flush.enabled"
        /**
         * `VOUCHER_MIGRATION_LOCK_KEY`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private const val VOUCHER_MIGRATION_LOCK_KEY = 534001L
    }

    init {
        // Spring Framework 7은 기본적으로 body-stream flush를 비활성화합니다. SSE는 각 event가 network에 도달해야 합니다.
        SpringProperties.setProperty(SPRING_HTTP_RESPONSE_FLUSH_ENABLED, "true")
    }

    @Bean
    /**
     * clock은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun clock(): Clock = Clock.systemUTC()

    @Bean("springTransactionManager")
    /**
     * springTransactionManager은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param dataSource 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean
    /**
     * voucherExposedDatabaseRegistration은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을
     * 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun voucherExposedDatabaseRegistration(
        @Qualifier("springTransactionManager")
        springTransactionManager: PlatformTransactionManager,
    ): VoucherExposedDatabaseRegistration =
        VoucherExposedDatabaseRegistration(
            TransactionTemplate(springTransactionManager).execute {
                TransactionManager.current().db
            },
        )

    @Bean(destroyMethod = "")
    /**
     * voucherExecutor은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun voucherExecutor(): ExecutorService =
        VirtualThreads.executorService().also {
            log.info { "voucher_executor_created virtualThreads=true" }
        }

    @Bean
    /**
     * voucherExecutorShutdown은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param executor 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun voucherExecutorShutdown(executor: ExecutorService): VoucherExecutorShutdown =
        VoucherExecutorShutdown(executor)

    @Bean
    /**
     * databasePermitGate은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun databasePermitGate(
        properties: VoucherProperties,
        metrics: VoucherMetrics,
    ): DatabasePermitGate =
        DatabasePermitGate(
            foregroundPermits = properties.db.foregroundPermits,
            workerPermits = 1,
            sseMaintenancePermits = properties.db.backgroundPermits - 1,
            acquireTimeout = properties.db.permitTimeout,
            metrics = metrics,
        )

    @Bean
    /**
     * voucherJdbcExecutor은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun voucherJdbcExecutor(
        gate: DatabasePermitGate,
        @Qualifier("springTransactionManager")
        transactionManager: PlatformTransactionManager,
        properties: VoucherProperties,
    ): VoucherJdbcExecutor =
        VoucherJdbcExecutor(
            gate = gate,
            transactionManager = transactionManager,
            lockTimeout = properties.db.lockTimeout
        )

    @Bean
    /**
     * voucherCodeService은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param properties 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun voucherCodeService(properties: VoucherProperties): VoucherCodeService =
        VoucherCodeService(
            VoucherCodeKeyRing(
                currentGenerationVersion = properties.keys.currentVersion,
                currentVerificationVersion = properties.keys.currentVersion,
                generationKeys = properties.keys.generation.mapValues { it.value.toUtf8Bytes() },
                verificationKeys = properties.keys.verification.mapValues { it.value.toUtf8Bytes() },
            ),
        )

    @Bean
    /**
     * voucherMigrationRunner은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun voucherMigrationRunner(
        dataSource: DataSource,
        properties: VoucherProperties,
    ): VoucherMigrationRunner =
        VoucherMigrationRunner(
            dataSource = dataSource,
            migration = VoucherMigration("001", ClassPathResource("db/migration/V001__voucher_campaign.sql")),
            advisoryLockKey = VOUCHER_MIGRATION_LOCK_KEY,
            lockTimeout = properties.db.lockTimeout,
        )

    @Bean
    /**
     * voucherSchemaMigration은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param runner 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun voucherSchemaMigration(runner: VoucherMigrationRunner): SmartInitializingSingleton =
        SmartInitializingSingleton {
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = runner.migrate()
            log.info { "voucher_schema_migration_completed result=$result" }
        }

    @Bean
    /**
     * referencedKeyVersionSource은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param dataSource 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun referencedKeyVersionSource(dataSource: DataSource): ReferencedKeyVersionSource =
        PostgresReferencedKeyVersionSource(dataSource)

    @Bean
    /**
     * voucherStartupValidation은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun voucherStartupValidation(
        properties: VoucherProperties,
        environment: Environment,
        referencedKeys: ObjectProvider<ReferencedKeyVersionSource>,
    ): SmartInitializingSingleton =
        SmartInitializingSingleton {
            /**
             * `activeProfiles`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
             *
             * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
             */
            val activeProfiles = environment.activeProfiles.toSet().ifEmpty { environment.defaultProfiles.toSet() }
            /**
             * `runtime`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val runtime = VoucherRuntimeEnvironment(
                activeProfiles = activeProfiles,
                serverAddress = environment.getProperty("server.address", "127.0.0.1"),
                hikariMaximumPoolSize = environment.getProperty<Int>(
                    "spring.datasource.hikari.maximum-pool-size",
                    16,
                ),
            )
            /**
             * `validator`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val validator = VoucherStartupValidator(
                referencedKeys.getIfAvailable() ?: ReferencedKeyVersionSource.NONE
            )
            try {
                validator.validate(properties, runtime)
            } catch (failure: RuntimeException) {
                log.warn { "voucher_startup_validation_failed code=${sanitizedStartupCode(failure)}" }
                throw failure
            }
            log.info { "voucher_startup_validation_passed profiles=${activeProfiles.sorted()}" }
        }
}

/** Spring context가 닫힐 때 Exposed process-wide database registration을 제거합니다. */
/** Spring context가 닫힐 때 Exposed process-wide database registration을 제거합니다. */
internal class VoucherExposedDatabaseRegistration(
    /**
     * `database`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val database: Database,
): AutoCloseable {
    /**
     * `closed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val closed = AtomicBoolean()

    init {
        TransactionManager.defaultDatabase = database
    }

    /**
     * close은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            TransactionManager.closeAndUnregister(database)
        }
    }
}

/** application-owned virtual-thread executor에 bounded shutdown을 적용합니다. */
/** application-owned virtual-thread executor에 bounded shutdown을 적용합니다. */
internal class VoucherExecutorShutdown(
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: ExecutorService,
    /**
     * `timeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val timeout: Duration = 10.seconds(),
): AutoCloseable {

    companion object: KLogging()

    /**
     * `closed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val closed = AtomicBoolean()

    /**
     * close은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeout.toKotlinDuration())) {
                log.warn { "voucher_executor_forced_shutdown timeoutMillis=${timeout.toMillis()}" }
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        log.info { "voucher_executor_shutdown" }
    }
}
