@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.performance

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.jdbc.sql.singleLong
import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionLimits
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionNamespace
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.admission.PermitLane
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionGate
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchSourceKind
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateCampaignCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateImportBatchCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ImportChunkCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcAllocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcCampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcRedemptionService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.applied
import io.bluetape4k.workshop.commerce.voucherpool.config.RecoverableVoucherPoolAdmissionBackend
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMetrics
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolRedisProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolRedisResources
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolSseProperties
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.JdbcVoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcExecutionLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcTimeoutPhase
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcVoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcMetrics
import io.bluetape4k.workshop.commerce.voucherpool.query.JdbcVoucherPoolQueryService
import io.bluetape4k.workshop.commerce.voucherpool.query.JdbcVoucherPoolQueryStore
import io.bluetape4k.workshop.commerce.voucherpool.security.AesGcmVoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKey
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKeyRing
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorage
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKek
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import io.bluetape4k.workshop.commerce.voucherpool.web.PostgresVoucherPoolEventSource
import io.bluetape4k.workshop.commerce.voucherpool.web.ReserveVoucherRequest
import io.bluetape4k.workshop.commerce.voucherpool.web.TenantPrincipal
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolApiException
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolEventStream
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolEventStream.StreamSubscription
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolHttpCommandExecutor
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolHttpProperties
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkerRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkers
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunRequest
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.core.io.ClassPathResource
import java.io.PrintWriter
import java.io.Serializable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `RedisMode`은 동작 모드입니다.
 * provider 또는 실행 정책을 선택하는 값이며 테스트·운영 시나리오의 분기 기준입니다.
 */
internal enum class RedisMode {
    /**
     * `RedisMode.HEALTHY`은 `healthy` 업무 상황을 나타냅니다. provider 또는 실행 시나리오를 선택하며 실제 업무 상태와 혼동하지 않습니다.
     */
    HEALTHY,
    /**
     * `RedisMode.UNAVAILABLE`은 필수 의존성이 준비되지 않아 요청을 수용할 수 없는 값입니다.
     */
    UNAVAILABLE,
}

/**
 * VoucherPoolStressEvidence 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class VoucherPoolStressEvidence(
/**
 * runId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val runId: String,
/**
 * clients은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val clients: Int,
/**
 * redisMode은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val redisMode: RedisMode,
/**
 * winners은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val winners: Int,
/**
 * successfulResponses은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val successfulResponses: Int,
/**
 * authoritativeReservationCount은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val authoritativeReservationCount: Int,
/**
 * authoritativeAllocationCount은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val authoritativeAllocationCount: Int,
/**
 * duplicateWinnerCount은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val duplicateWinnerCount: Int,
/**
 * stateCountSum은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val stateCountSum: Int,
/**
 * entryCount은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val entryCount: Int,
/**
 * hikariActiveMax은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val hikariActiveMax: Int,
/**
 * hikariAcquisitionWaitMaxMillis은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val hikariAcquisitionWaitMaxMillis: Long,
/**
 * hikariAcquisitionWaitSamples은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val hikariAcquisitionWaitSamples: Long,
/**
 * totalPermitHoldersMax은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val totalPermitHoldersMax: Int,
/**
 * foregroundWaitMaxMillis은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val foregroundWaitMaxMillis: Long,
/**
 * foregroundWaitSamples은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val foregroundWaitSamples: Long,
/**
 * workerWaitMaxMillis은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val workerWaitMaxMillis: Long,
/**
 * workerWaitSamples은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val workerWaitSamples: Long,
/**
 * sseWaitMaxMillis은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sseWaitMaxMillis: Long,
/**
 * sseWaitSamples은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sseWaitSamples: Long,
/**
 * acquisitionTimeouts은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val acquisitionTimeouts: Int,
/**
 * acquisitionTimeoutTerminalDescriptors은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val acquisitionTimeoutTerminalDescriptors: Int,
/**
 * deadlineViolations은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deadlineViolations: Int,
/**
 * hikariPendingMax은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val hikariPendingMax: Int,
/**
 * hikariPendingDrainMillis은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val hikariPendingDrainMillis: Long,
/**
 * workerCheckpointProgress은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val workerCheckpointProgress: Long,
/**
 * connectionLeaks은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val connectionLeaks: Int,
/**
 * permitLeaks은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val permitLeaks: Int,
/**
 * counterDrift은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val counterDrift: Long,
): Serializable {
    companion object {
/**
 * serialVersionUID은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val serialVersionUID: Long = 2L
    }
}

/**
 * HikariTimeoutEvidence 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class HikariTimeoutEvidence(
/**
 * status은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val status: Int,
/**
 * code은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val code: String,
/**
 * retryable은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val retryable: Boolean,
/**
 * ownerReleased은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val ownerReleased: Boolean,
/**
 * terminalDescriptorWritten은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val terminalDescriptorWritten: Boolean,
): Serializable {
    companion object {
/**
 * serialVersionUID은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * StressProfileRun 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private data class StressProfileRun(
/**
 * evidence은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val evidence: VoucherPoolStressEvidence,
/**
 * threadDump은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val threadDump: String,
)

@Suppress("LongMethod", "LongParameterList", "NestedBlockDepth", "TooManyFunctions", "SqlResolve")
/**
 * VoucherPoolPerformanceProbe 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolPerformanceProbe(
/**
 * postgres은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val postgres: PostgreSQLServer,
/**
 * outputRoot은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val outputRoot: Path,
/**
 * redis은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redis: RedisServer = RedisServer.Launcher.redis,
) {
/**
 * runProfile은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun runProfile(
        runId: String,
        entries: Int,
        clients: Int,
        sameUserPercent: Int,
        redis: RedisMode,
        workerLoad: Boolean,
        sseSubscribers: Int,
        freshDatabase: Boolean,
    ): VoucherPoolStressEvidence {
        require(entries > clients)
        require(sameUserPercent in 0..100)
        require(freshDatabase) { "stress evidence requires a fresh database schema" }
/**
 * profile은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val profile = "${clients}-${redis.name.lowercase()}"
/**
 * schema은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val schema = schemaName(runId, profile)
        createSchema(schema)
/**
 * dataSource은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val dataSource = dataSource(schema, profile)
/**
 * harness은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var harness: ActualVoucherPoolStressHarness? = null

        return try {
            migrate(dataSource)
            harness = ActualVoucherPoolStressHarness(dataSource, this.redis, profile, clients, redis)
            harness.createPool(entries)
            prewarm(dataSource)
/**
 * result은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val result = harness.exercise(
                runId = runId,
                entries = entries,
                clients = clients,
                sameUserPercent = sameUserPercent,
                workerLoad = workerLoad,
                sseSubscribers = sseSubscribers,
            )
            StressEvidenceWriter(outputRoot).write(result.evidence, result.threadDump)
            result.evidence
        } finally {
            harness?.close()
            dataSource.close()
            dropSchema(schema)
        }
    }

/**
 * acquisitionTimeoutSemantics은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun acquisitionTimeoutSemantics(): HikariTimeoutEvidence {
        val schema = schemaName("timeout", System.nanoTime().toString())
        createSchema(schema)
        val dataSource = dataSource(
            schema = schema,
            profile = "timeout",
            maximumPoolSize = TIMEOUT_POOL_SIZE,
            connectionTimeoutMillis = TIMEOUT_ACQUISITION_MILLIS,
        )
        return try {
            migrate(dataSource)
            ActualVoucherPoolStressHarness(
                dataSource = dataSource,
                redisServer = redis,
                profile = "timeout-fixture",
                clients = 1,
                redisMode = RedisMode.UNAVAILABLE,
            ).use {
                it.createPool(TIMEOUT_FIXTURE_ENTRIES)
            }

            val blockingDataSource = OneShotAcquisitionTimeoutDataSource(dataSource, TIMEOUT_POOL_SIZE)
            val metrics = RecordingJdbcMetrics()
            val graph = ServiceGraph(blockingDataSource, DatabasePermitGate.default(HIKARI_POOL_SIZE), metrics)

            val failure = runCatching {
                graph.http.reserve(
                    TenantPrincipal(graph.tenant, graph.principal),
                    timeoutCampaignId(),
                    TIMEOUT_IDEMPOTENCY_KEY,
                    "*",
                    ReserveVoucherRequest(),
                    "timeout-request",
                )
            }.exceptionOrNull()
            blockingDataSource.awaitHolder()
            failure.shouldBeInstanceOf<VoucherPoolApiException>()
            metrics.acquisitionTimeouts.get() shouldBeGreaterOrEqualTo 2 // the command and at least one owner-release attempt must observe acquisition timeouts

            val owner = timeoutOwnerState(dataSource)
            HikariTimeoutEvidence(
                status = failure.status,
                code = failure.stableCode,
                retryable = failure.retryAfterSeconds != null,
                ownerReleased = owner.status == "RETRYABLE_FAILED" && owner.ownerTokenDigest == null,
                terminalDescriptorWritten = owner.descriptor != null,
            )
        } finally {
            dataSource.close()
            dropSchema(schema)
        }
    }

/**
 * timeoutOwnerState은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun timeoutOwnerState(dataSource: HikariDataSource): TimeoutOwnerState {
        val sql = """
            SELECT status,owner_token_digest,descriptor::text 
              FROM voucher_pool_http_idempotency 
             WHERE tenant_id=? AND operation='voucher-reserve'
            """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, TIMEOUT_TENANT)
                stmt.executeQuery().use { result ->
                    result.next()
                    TimeoutOwnerState(
                        status = result.getString(1),
                        ownerTokenDigest = result.getBytes(2),
                        descriptor = result.getString(3)
                    )
                }
            }
        }
    }

/**
 * migrate은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun migrate(dataSource: HikariDataSource) {
        VoucherPoolMigrationRunner(
            dataSource = dataSource,
            migration = VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            advisoryLockKey = MIGRATION_LOCK,
        ).migrate()
    }

/**
 * prewarm은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun prewarm(dataSource: HikariDataSource) {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            List(dataSource.maximumPoolSize) {
                executor.submit<Boolean> {
                    dataSource.connection.use { conn ->
                        conn.isValid(2)
                    }
                }
            }.forEach {
                it.get().shouldBeTrue()
            }
        }
    }

/**
 * dataSource은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun dataSource(
        schema: String,
        profile: String,
        maximumPoolSize: Int = HIKARI_POOL_SIZE,
        connectionTimeoutMillis: Long = HIKARI_ACQUISITION_DEADLINE_MILLIS,
    ): HikariDataSource =
        postgres.getHikariDataSource {
            this.maximumPoolSize = maximumPoolSize
            minimumIdle = maximumPoolSize
            connectionTimeout = connectionTimeoutMillis
            poolName = "voucher-stress-$profile"
            connectionInitSql = "SET search_path TO $schema"
        }

/**
 * createSchema은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createSchema(schema: String) =
        adminConnection().use { conn ->
            conn.createStatement().use {
                it.execute("DROP SCHEMA IF EXISTS $schema CASCADE; CREATE SCHEMA $schema")
            }
        }

/**
 * dropSchema은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun dropSchema(schema: String) =
        adminConnection().use { conn ->
            conn.createStatement().use {
                it.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }

/**
 * adminConnection은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun adminConnection(): Connection =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )

/**
 * schemaName은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun schemaName(runId: String, profile: String): String =
        "stress_${(runId + profile).replace(Regex("[^A-Za-z0-9]"), "_").lowercase()}_${System.nanoTime()}"

/**
 * TimeoutOwnerState 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class TimeoutOwnerState(
/**
 * status은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val status: String,
/**
 * ownerTokenDigest은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val ownerTokenDigest: ByteArray?,
/**
 * descriptor은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val descriptor: String?,
    )

    companion object: KLogging() {
/**
 * HIKARI_POOL_SIZE은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val HIKARI_POOL_SIZE = 16
/**
 * HIKARI_ACQUISITION_DEADLINE_MILLIS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val HIKARI_ACQUISITION_DEADLINE_MILLIS = 2_000L
/**
 * TIMEOUT_ACQUISITION_MILLIS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TIMEOUT_ACQUISITION_MILLIS = 250L
/**
 * TIMEOUT_POOL_SIZE은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TIMEOUT_POOL_SIZE = 4
/**
 * TIMEOUT_FIXTURE_ENTRIES은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TIMEOUT_FIXTURE_ENTRIES = 10
/**
 * MIGRATION_LOCK은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val MIGRATION_LOCK = 537_013L
/**
 * TIMEOUT_IDEMPOTENCY_KEY은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TIMEOUT_IDEMPOTENCY_KEY = "stress-timeout-reserve"
/**
 * TIMEOUT_TENANT은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TIMEOUT_TENANT = "tenant-timeout-fixture"

/**
 * timeoutCampaignId은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun timeoutCampaignId(): UUID = UUID.nameUUIDFromBytes("timeout-fixture-campaign".toUtf8Bytes())
    }
}

@Suppress("LongMethod", "LongParameterList", "TooManyFunctions", "SqlResolve")
/**
 * ActualVoucherPoolStressHarness 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class ActualVoucherPoolStressHarness(
/**
 * dataSource은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val dataSource: HikariDataSource,
    redisServer: RedisServer,
/**
 * profile은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val profile: String,
/**
 * clients은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val clients: Int,
    redisMode: RedisMode,
): AutoCloseable {
/**
 * tenant은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val tenant = "tenant-$profile"
/**
 * campaignId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val campaignId: UUID = UUID.nameUUIDFromBytes("$profile-campaign".toUtf8Bytes())
/**
 * batchId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val batchId: UUID = UUID.nameUUIDFromBytes("$profile-batch".toUtf8Bytes())
/**
 * metrics은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val metrics = RecordingJdbcMetrics()
/**
 * hikariAcquisitionWaits은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val hikariAcquisitionWaits = ObservedWaitRecorder()
/**
 * measuredDataSource은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val measuredDataSource = ObservedWaitDataSource(dataSource, hikariAcquisitionWaits)
/**
 * gate은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val gate = DatabasePermitGate.default(HIKARI_POOL_SIZE)
/**
 * graph은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val graph = ServiceGraph(measuredDataSource, gate, metrics, tenant, campaignId)
/**
 * redisProperties은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redisProperties = redisProperties(redisServer, clients, redisMode)
/**
 * redisResources은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redisResources = openRedisResources(redisServer, redisProperties, redisMode)
/**
 * admission은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val admission = VoucherPoolAdmissionGate(
        backend = RecoverableVoucherPoolAdmissionBackend(redisResources.client, redisProperties),
        limits = redisProperties.limits,
    )
/**
 * properties은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val properties = stressProperties(redisProperties)
/**
 * http은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val http = graph.http(admission)
/**
 * eventSource은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val eventSource = PostgresVoucherPoolEventSource(graph.executor, graph.digests, JSON, properties)
/**
 * workers은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val workers = JdbcVoucherPoolWorkers(graph.executor, graph.workerClaims, graph.repository)

/**
 * createPool은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun createPool(entries: Int) {
        val manifest = digest("manifest-$profile-$entries")
        val firstChunkSize = minOf(CHUNK_SIZE, entries)
        val firstCodes = codes(0, firstChunkSize)
        val campaign = graph.commands.createCampaign(
            CreateCampaignCommand(
                tenantId = tenant,
                campaignId = campaignId,
                startsAt = Instant.now().minusSeconds(60),
                endsAt = Instant.now().plusSeconds(3_600),
                policy = VoucherPoolPolicy.of(clients, 5.minutes, 10.minutes, 1),
                idempotencyKey = key("create-campaign"),
            ),
        ).applied()

        graph.commands.activateCampaign(
            CampaignRevisionCommand(tenant, campaignId, campaign.revision, key("activate-campaign")),
        ).applied()

        var batch = graph.commands.createImportBatch(
            CreateImportBatchCommand(
                tenantId = tenant,
                batchId = batchId,
                campaignId = campaignId,
                sourceKind = BatchSourceKind.IMPORTED,
                manifestDigest = manifest,
                requestFingerprint = digest("request-$profile-$entries"),
                expectedCount = entries.toLong(),
                activatesAt = Instant.now().minusSeconds(1),
                initialCodes = firstCodes,
                idempotencyKey = key("create-batch"),
            ),
        ).applied()

        var firstOrdinal = firstChunkSize
        while (firstOrdinal < entries) {
            val count = minOf(CHUNK_SIZE, entries - firstOrdinal)
            batch = graph.commands.importChunk(
                ImportChunkCommand(
                    tenantId = tenant,
                    batchId = batchId,
                    campaignId = campaignId,
                    firstOrdinal = firstOrdinal.toLong(),
                    manifestDigest = manifest,
                    codes = codes(firstOrdinal, count),
                    expectedRevision = batch.revision,
                    idempotencyKey = key("chunk-$firstOrdinal"),
                ),
            ).applied()
            firstOrdinal += count
        }
        graph.commands.activateBatch(
            BatchRevisionCommand(
                tenantId = tenant,
                campaignId = campaignId,
                batchId = batchId,
                expectedRevision = batch.revision,
                idempotencyKey = key("activate-batch")
            ),
        ).applied()
    }

/**
 * exercise은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun exercise(
        runId: String,
        entries: Int,
        clients: Int,
        sameUserPercent: Int,
        workerLoad: Boolean,
        sseSubscribers: Int,
    ): StressProfileRun {
        hikariAcquisitionWaits.reset()
        gate.resetWaitObservations()
/**
 * samplerRunning은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val samplerRunning = AtomicBoolean(true)
/**
 * permitHoldersMax은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val permitHoldersMax = AtomicInteger()
/**
 * hikariActiveMax은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val hikariActiveMax = AtomicInteger()
/**
 * hikariPendingMax은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val hikariPendingMax = AtomicInteger()
/**
 * start은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val start = CountDownLatch(1)
/**
 * loadStarted은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val loadStarted = CountDownLatch(clients + sseSubscribers + if (workerLoad) 1 else 0)
/**
 * loadThreads은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val loadThreads = ConcurrentHashMap.newKeySet<Thread>()
/**
 * subscriptions은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val subscriptions = ConcurrentLinkedQueue<AutoCloseable>()
/**
 * deadlineViolations은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val deadlineViolations = AtomicInteger()
/**
 * winners은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val winners = ConcurrentHashMap.newKeySet<UUID>()

/**
 * eventExecutor은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val eventExecutor = Executors.newVirtualThreadPerTaskExecutor()
/**
 * stream은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val stream = VoucherPoolEventStream(
            eventSource,
            JSON,
            eventExecutor,
            properties,
            VoucherPoolMetrics(SimpleMeterRegistry()),
        )
/**
 * loadExecutor은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val loadExecutor = Executors.newVirtualThreadPerTaskExecutor()
        try {
/**
 * sampler은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val sampler = loadExecutor.submit<Unit> {
                while (samplerRunning.get()) {
/**
 * snapshot은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val snapshot = gate.snapshot()
                    permitHoldersMax.accumulateAndGet(
                        snapshot.foregroundInUse + snapshot.workerInUse + snapshot.sseInUse,
                        ::maxOf,
                    )
                    hikariActiveMax.accumulateAndGet(dataSource.hikariPoolMXBean.activeConnections, ::maxOf)
                    hikariPendingMax.accumulateAndGet(dataSource.hikariPoolMXBean.threadsAwaitingConnection, ::maxOf)
                    Thread.sleep(SAMPLE_INTERVAL_MILLIS)
                }
            }
/**
 * clientFutures은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val clientFutures = List(clients) { index ->
                loadExecutor.submit<UUID> {
                    start.await()
                    loadThreads += Thread.currentThread()
                    loadStarted.countDown()
/**
 * user은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val user = if (index * 100 / clients < sameUserPercent) SHARED_USER else "user-$index"
/**
 * principal은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val principal = TenantPrincipal(tenant, user)
/**
 * reservation은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val reservation = retryableHttp {
                        checkNotNull(
                            http.reserve(
                                principal = principal,
                                campaignId = campaignId,
                                idempotencyKey = key("reserve-$index"),
                                ifNoneMatch = "*",
                                request = ReserveVoucherRequest(),
                                requestId = "reserve-request-$index",
                            ).body,
                        )
                    }
/**
 * allocation은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val allocation = retryableHttp {
                        checkNotNull(
                            http.allocate(
                                principal = principal,
                                reservationId = reservation.reservationId,
                                idempotencyKey = key("allocate-$index"),
                                expectedRevision = "\"${reservation.revision}\"",
                                requestId = "allocate-request-$index",
                            ).body,
                        )
                    }
                    allocation.allocationId
                }
            }
/**
 * workerFuture은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val workerFuture = if (workerLoad) {
                loadExecutor.submit<Long> {
                    start.await()
                    loadThreads += Thread.currentThread()
                    loadStarted.countDown()
/**
 * outcome은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val outcome = workers.run(
                        WorkerRunRequest(
                            tenantId = tenant,
                            kind = WorkerKind.RECONCILIATION,
                            scopeId = batchId,
                            owner = "stress-worker-$profile",
                            requestedLimit = WORKER_CHUNK_SIZE,
                        ),
                    )
                    check(outcome.state == WorkerRunState.COMPLETED) { "actual worker must complete" }
                    checkNotNull(outcome.claim).checkpoint
                }
            } else {
                null
            }
/**
 * sseFutures은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val sseFutures = List(sseSubscribers) { index ->
                loadExecutor.submit<StreamSubscription> {
                    start.await()
                    loadThreads += Thread.currentThread()
                    loadStarted.countDown()
                    stream.openCustomer(tenant, "sse-user-$index", null)
                        .also { subscription ->
                            check(checkNotNull(subscription.next(Duration.ZERO)).type == "snapshot")
                            subscriptions += subscription
                        }
                }
            }

            start.countDown()

            // "all production-path stress tasks must start before evidence capture"
            loadStarted.await(LOAD_START_SECONDS.seconds).shouldBeTrue()

/**
 * underLoadThreadDump은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val underLoadThreadDump = stressThreadDump(profile, loadThreads)
/**
 * outcomes은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val outcomes = clientFutures.map { future ->
                getBeforeDeadline(future, deadlineViolations)
            }
            winners += authoritativeWinnerEntryIds()

/**
 * workerCheckpoint은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val workerCheckpoint = workerFuture?.let { getBeforeDeadline(it, deadlineViolations) } ?: 0L
            sseFutures.forEach { getBeforeDeadline(it, deadlineViolations) }

            Thread.sleep(SSE_ACTIVE_MILLIS)
            subscriptions.forEach(AutoCloseable::close)
            stream.close()
            samplerRunning.set(false)
            sampler.get(SAMPLER_STOP_SECONDS.seconds)

/**
 * drainStarted은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val drainStarted = System.nanoTime()
            while (dataSource.hikariPoolMXBean.threadsAwaitingConnection > 0 || dataSource.hikariPoolMXBean.activeConnections > 0) {
                if (elapsedMillis(drainStarted) > HIKARI_DRAIN_DEADLINE_MILLIS) {
                    deadlineViolations.incrementAndGet()
                    break
                }
                Thread.sleep(DRAIN_POLL_MILLIS)
            }

/**
 * drainMillis은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val drainMillis = elapsedMillis(drainStarted)
/**
 * counts은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val counts = authoritativeCounts()
/**
 * permitSnapshot은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val permitSnapshot = gate.snapshot()
/**
 * hikariAcquisitionWait은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val hikariAcquisitionWait = hikariAcquisitionWaits.snapshot()
/**
 * permitLeaks은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val permitLeaks = permitSnapshot.foregroundInUse + permitSnapshot.workerInUse + permitSnapshot.sseInUse

            return StressProfileRun(
                evidence = VoucherPoolStressEvidence(
                    runId = runId,
                    clients = clients,
                    redisMode = redisMode(),
                    winners = winners.size,
                    successfulResponses = outcomes.size,
                    authoritativeReservationCount = counts.reservations,
                    authoritativeAllocationCount = counts.allocations,
                    duplicateWinnerCount = outcomes.size - winners.size,
                    stateCountSum = counts.stateCountSum,
                    entryCount = entries,
                    hikariActiveMax = hikariActiveMax.get(),
                    hikariAcquisitionWaitMaxMillis = hikariAcquisitionWait.maxMillis,
                    hikariAcquisitionWaitSamples = hikariAcquisitionWait.samples,
                    totalPermitHoldersMax = permitHoldersMax.get(),
                    foregroundWaitMaxMillis = permitSnapshot.observedWaitMax.getValue(PermitLane.FOREGROUND)
                        .ceilingMillis(),
                    foregroundWaitSamples = permitSnapshot.observedWaitSamples.getValue(PermitLane.FOREGROUND),
                    workerWaitMaxMillis = permitSnapshot.observedWaitMax.getValue(PermitLane.WORKER).ceilingMillis(),
                    workerWaitSamples = permitSnapshot.observedWaitSamples.getValue(PermitLane.WORKER),
                    sseWaitMaxMillis = permitSnapshot.observedWaitMax.getValue(PermitLane.SSE).ceilingMillis(),
                    sseWaitSamples = permitSnapshot.observedWaitSamples.getValue(PermitLane.SSE),
                    acquisitionTimeouts = metrics.acquisitionTimeouts.get(),
                    acquisitionTimeoutTerminalDescriptors = counts.acquisitionTimeoutTerminalDescriptors,
                    deadlineViolations = deadlineViolations.get() + metrics.deadlineTimeouts.get(),
                    hikariPendingMax = hikariPendingMax.get(),
                    hikariPendingDrainMillis = drainMillis,
                    workerCheckpointProgress = workerCheckpoint,
                    connectionLeaks = dataSource.hikariPoolMXBean.activeConnections,
                    permitLeaks = permitLeaks,
                    counterDrift = counts.counterDrift,
                ),
                threadDump = underLoadThreadDump,
            )
        } finally {
            samplerRunning.set(false)
            loadExecutor.shutdownNow()
            subscriptions.forEach(AutoCloseable::close)
            stream.close()
            eventExecutor.shutdownNow()
        }
    }

/**
 * authoritativeCounts은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun authoritativeCounts(): AuthoritativeCounts = dataSource.connection.use { conn ->
/**
 * reservations은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val reservations = conn.scalar("SELECT count(*) FROM voucher_pool_reservations WHERE tenant_id=?", tenant)
/**
 * allocations은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val allocations = conn.scalar("SELECT count(*) FROM voucher_pool_allocations WHERE tenant_id=?", tenant)
/**
 * stateCountSum은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val stateCountSum = conn.scalar("SELECT count(*) FROM voucher_pool_entries WHERE tenant_id=?", tenant)
/**
 * activeAllocations은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val activeAllocations = conn.scalar(
            "SELECT coalesce(sum(active_allocations),0) FROM voucher_pool_user_limits WHERE tenant_id=?",
            tenant
        )
/**
 * poolDepth은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val poolDepth = conn.scalar(
            "SELECT coalesce(sum(entry_count),0) FROM voucher_pool_pool_depth WHERE tenant_id=?",
            tenant,
        )
/**
 * timeoutDescriptors은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val timeoutDescriptors = conn.scalar(
            "SELECT count(*) FROM voucher_pool_http_idempotency WHERE tenant_id=? AND descriptor::text LIKE '%BACKEND_TIMEOUT%'",
            tenant,
        )
        AuthoritativeCounts(
            reservations = reservations.toInt(),
            allocations = allocations.toInt(),
            stateCountSum = stateCountSum.toInt(),
            acquisitionTimeoutTerminalDescriptors = timeoutDescriptors.toInt(),
            counterDrift = abs(activeAllocations - allocations) + abs(poolDepth - stateCountSum),
        )
    }

/**
 * authoritativeWinnerEntryIds은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun authoritativeWinnerEntryIds(): Set<UUID> = dataSource.connection.use { conn ->
/**
 * sql은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val sql = "SELECT entry_id FROM voucher_pool_allocations WHERE tenant_id=?"

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, tenant)
            stmt.executeQuery().use { result ->
                buildSet {
                    while (result.next()) {
                        add(result.getObject(1, UUID::class.java))
                    }
                }
            }
        }
    }

/**
 * scalar은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun Connection.scalar(sql: String, tenantId: String): Long =
        prepareStatement(sql).use { stmt ->
            stmt.setString(1, tenantId)
            stmt.executeQuery().use { result ->
                result.singleLong()
            }
        }

/**
 * codes은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun codes(firstOrdinal: Int, count: Int): List<String> = List(count) { offset ->
        "VP-$profile-${firstOrdinal + offset}"
    }

/**
 * key은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun key(suffix: String): String = "stress-$profile-$suffix"

/**
 * retryableHttp은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun <T> retryableHttp(block: () -> T): T {
        val startedAt = System.nanoTime()
        while (true) {
            try {
                return block()
            } catch (failure: VoucherPoolApiException) {
                if (failure.stableCode !in RETRYABLE_HTTP_CODES || elapsedMillis(startedAt) >= PROFILE_DEADLINE_MILLIS) {
                    throw failure
                }
                Thread.sleep(RETRY_BACKOFF_MILLIS)
            }
        }
    }

/**
 * digest은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun digest(value: String): DigestValue =
        DigestValue(MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()))

/**
 * redisMode은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun redisMode(): RedisMode =
        if (redisProperties.uri.endsWith(UNAVAILABLE_REDIS_PORT)) RedisMode.UNAVAILABLE else RedisMode.HEALTHY

/**
 * close은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        redisResources.close()
    }

/**
 * AuthoritativeCounts 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class AuthoritativeCounts(
/**
 * reservations은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val reservations: Int,
/**
 * allocations은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val allocations: Int,
/**
 * stateCountSum은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val stateCountSum: Int,
/**
 * acquisitionTimeoutTerminalDescriptors은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val acquisitionTimeoutTerminalDescriptors: Int,
/**
 * counterDrift은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val counterDrift: Long,
    )

    companion object: KLogging() {
/**
 * HIKARI_POOL_SIZE은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val HIKARI_POOL_SIZE = 16
/**
 * CHUNK_SIZE은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val CHUNK_SIZE = 500
/**
 * WORKER_CHUNK_SIZE은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val WORKER_CHUNK_SIZE = 50
/**
 * SAMPLE_INTERVAL_MILLIS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val SAMPLE_INTERVAL_MILLIS = 2L
/**
 * SSE_ACTIVE_MILLIS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val SSE_ACTIVE_MILLIS = 300L
/**
 * DRAIN_POLL_MILLIS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val DRAIN_POLL_MILLIS = 5L
/**
 * HIKARI_DRAIN_DEADLINE_MILLIS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val HIKARI_DRAIN_DEADLINE_MILLIS = 12_000L
/**
 * PROFILE_DEADLINE_SECONDS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROFILE_DEADLINE_SECONDS = 30L
/**
 * PROFILE_DEADLINE_MILLIS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROFILE_DEADLINE_MILLIS = PROFILE_DEADLINE_SECONDS * 1_000L
/**
 * LOAD_START_SECONDS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val LOAD_START_SECONDS = 5L
/**
 * SAMPLER_STOP_SECONDS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val SAMPLER_STOP_SECONDS = 2L
/**
 * RETRY_BACKOFF_MILLIS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val RETRY_BACKOFF_MILLIS = 10L
/**
 * SHARED_USER은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val SHARED_USER = "same-user"
/**
 * UNAVAILABLE_REDIS_PORT은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val UNAVAILABLE_REDIS_PORT = ":1"
/**
 * RETRYABLE_HTTP_CODES은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val RETRYABLE_HTTP_CODES = setOf("BACKEND_TIMEOUT", "POOL_BUSY", "RATE_LIMITED", "COMMAND_IN_PROGRESS")
/**
 * JSON은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val JSON = Jackson.defaultJsonMapper

/**
 * getBeforeDeadline은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun <T> getBeforeDeadline(
            future: java.util.concurrent.Future<T>,
            deadlineViolations: AtomicInteger,
        ): T = try {
            future.get(PROFILE_DEADLINE_SECONDS.seconds)
        } catch (failure: java.util.concurrent.TimeoutException) {
            deadlineViolations.incrementAndGet()
            throw failure
        }

/**
 * elapsedMillis은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun elapsedMillis(startedAt: Long): Long =
            (System.nanoTime() - startedAt).nanos().toMillis()

/**
 * stressLimits은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun stressLimits(@Suppress("UNUSED_PARAMETER") clients: Int): AdmissionLimits {
            var limits = AdmissionLimits.defaults()
            AdmissionNamespace.entries.forEach { namespace ->
                limits = limits.withLimit(namespace, STRESS_ADMISSION_LIMIT)
            }
            return limits
        }

/**
 * STRESS_ADMISSION_LIMIT은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val STRESS_ADMISSION_LIMIT = 10_000

/**
 * redisProperties은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun redisProperties(
            redisServer: RedisServer,
            clients: Int,
            mode: RedisMode,
        ): VoucherPoolRedisProperties =
            VoucherPoolRedisProperties(
                enabled = true,
                uri = if (mode == RedisMode.HEALTHY) redisServer.url else "redis://127.0.0.1:1",
                commandTimeout = (if (mode == RedisMode.HEALTHY) 500 else 50).millis(),
                limits = stressLimits(clients),
            )

/**
 * openRedisResources은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun openRedisResources(
            redisServer: RedisServer,
            properties: VoucherPoolRedisProperties,
            mode: RedisMode,
        ): VoucherPoolRedisResources {
            if (mode == RedisMode.HEALTHY) {
/**
 * client은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val client = LettuceClients.clientOf(redisServer.url)
                try {
                    LettuceClients.connect(client).use { it.sync().flushall() }
                } finally {
                    LettuceClients.shutdown(client)
                }
            }
            return VoucherPoolRedisResources.open(properties)
        }

/**
 * stressProperties은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun stressProperties(redis: VoucherPoolRedisProperties): VoucherPoolProperties =
            VoucherPoolProperties(
                redis = redis,
                sse = VoucherPoolSseProperties(
                    pollInterval = 50.millis(),
                    maxIdleInterval = 100.millis(),
                    heartbeatInterval = 1.seconds(),
                ),
                http = VoucherPoolHttpProperties(
                    operatorSecret = "stress-operator-secret-0000000000000001",
                    operatorGuard = "stress-operator-guard",
                ),
            )
    }
}

@Suppress("LongParameterList")
/**
 * ServiceGraph 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class ServiceGraph(
    dataSource: DataSource,
    gate: DatabasePermitGate,
    metrics: RecordingJdbcMetrics,
/**
 * tenant은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val tenant: String = TIMEOUT_TENANT,
/**
 * campaignId은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val campaignId: UUID = TIMEOUT_CAMPAIGN_ID,
) {
/**
 * principal은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val principal = "timeout-principal"
/**
 * executor은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val executor = VoucherPoolJdbcExecutor(
        gate = gate,
        transactionManager = SpringTransactionManager(dataSource, DatabaseConfig {}, false),
        metrics = metrics,
    )
/**
 * repository은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val repository = JdbcVoucherPoolRepository()
/**
 * digests은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val digests = digestService()
/**
 * idempotency은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val idempotency = JdbcVoucherPoolIdempotencyRepository(digests)

/**
 * crypto은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val crypto = AesGcmVoucherEnvelopeCrypto(
        kekRing = VoucherKekRing(VoucherKek("stress-test-kek", keyBytes(11))),
        digests = digests,
    )
/**
 * commands은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val commands = JdbcCampaignBatchCommandService(executor, repository, idempotency, digests, crypto)
/**
 * reservations은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reservations = JdbcReservationService(executor, repository, idempotency, digests)
/**
 * allocations은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val allocations = JdbcAllocationService(
        executor = executor,
        repository = repository,
        idempotency = idempotency,
        digests = digests,
        cryptoStorage = VoucherCryptoStorage(repository, crypto),
    )
/**
 * redemptions은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redemptions = JdbcRedemptionService(executor, repository, idempotency, digests)
/**
 * queries은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val queries = JdbcVoucherPoolQueryService(executor, JdbcVoucherPoolQueryStore(), digests)
/**
 * workerClaims은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val workerClaims = JdbcVoucherPoolWorkerRepository(executor)
/**
 * http은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val http = http(VoucherPoolAdmissionGate(null, stressLimits()))

/**
 * http은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun http(admission: VoucherPoolAdmissionGate): VoucherPoolHttpCommandExecutor =
        VoucherPoolHttpCommandExecutor(
            reservations = reservations,
            allocations = allocations,
            redemptions = redemptions,
            queries = queries,
            admission = admission,
            digests = digests
        )

    companion object: KLogging() {
/**
 * TIMEOUT_TENANT은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TIMEOUT_TENANT = "tenant-timeout-fixture"
/**
 * TIMEOUT_CAMPAIGN_ID은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val TIMEOUT_CAMPAIGN_ID = UUID.nameUUIDFromBytes("timeout-fixture-campaign".toUtf8Bytes())

/**
 * stressLimits은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun stressLimits(): AdmissionLimits {
            var limits = AdmissionLimits.defaults()
            AdmissionNamespace.entries.forEach {
                limits = limits.withLimit(it, 1_000)
            }
            return limits
        }

/**
 * keyBytes은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun keyBytes(seed: Int): ByteArray = ByteArray(32) { (seed + it).toByte() }

/**
 * digestService은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun digestService(): VoucherDigestService =
            VoucherDigestService(
                stableDedupKey = DigestKey.of(7, keyBytes(7)),
                commandTombstoneKey = DigestKey.of(4, keyBytes(4)),
                rotatingKeys = mapOf(
                    DigestPurpose.VERIFICATION to DigestKeyRing.of(DigestKey.of(1, keyBytes(1))),
                    DigestPurpose.USER_IDENTITY to DigestKeyRing.of(DigestKey.of(2, keyBytes(2))),
                    DigestPurpose.REDIS_SIGNAL to DigestKeyRing.of(DigestKey.of(3, keyBytes(3))),
                    DigestPurpose.AUDIT to DigestKeyRing.of(DigestKey.of(5, keyBytes(5))),
                ),
            )
    }
}

/**
 * RecordingJdbcMetrics 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class RecordingJdbcMetrics: VoucherPoolJdbcMetrics {
/**
 * acquisitionTimeouts은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val acquisitionTimeouts = AtomicInteger()
/**
 * permitTimeouts은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val permitTimeouts = AtomicInteger()
/**
 * deadlineTimeouts은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deadlineTimeouts = AtomicInteger()

/**
 * timedOut은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun timedOut(
        lane: JdbcExecutionLane,
        phase: JdbcTimeoutPhase,
    ) {
        when (phase) {
            JdbcTimeoutPhase.ACQUISITION -> acquisitionTimeouts.incrementAndGet()
            JdbcTimeoutPhase.PERMIT -> permitTimeouts.incrementAndGet()

            JdbcTimeoutPhase.LOCK,
            JdbcTimeoutPhase.TRANSACTION,
                                    -> deadlineTimeouts.incrementAndGet()
        }
    }
}

/**
 * ObservedWaitSnapshot 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private data class ObservedWaitSnapshot(
/**
 * samples은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val samples: Long,
/**
 * maxMillis은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val maxMillis: Long,
)

/**
 * ObservedWaitRecorder 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class ObservedWaitRecorder {

    companion object: KLogging()

/**
 * samples은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val samples = AtomicLong()
/**
 * maxNanos은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val maxNanos = AtomicLong()

/**
 * record은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun record(startedAtNanos: Long) {
        val elapsedNanos = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L)
        samples.incrementAndGet()
        maxNanos.accumulateAndGet(elapsedNanos, ::maxOf)
    }

/**
 * reset은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun reset() {
        samples.set(0L)
        maxNanos.set(0L)
    }

/**
 * snapshot은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun snapshot(): ObservedWaitSnapshot =
        ObservedWaitSnapshot(
            samples = samples.get(),
            maxMillis = maxNanos.get().ceilingMillisFromNanos(),
        )
}

/**
 * ObservedWaitDataSource 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class ObservedWaitDataSource(
/**
 * delegate은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val delegate: DataSource,
/**
 * waits은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val waits: ObservedWaitRecorder,
): DataSource {

    companion object: KLogging()

/**
 * getConnection은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getConnection(): Connection {
        val startedAt = System.nanoTime()
        return try {
            delegate.connection
        } finally {
            waits.record(startedAt)
        }
    }

/**
 * getConnection은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getConnection(username: String?, password: String?): Connection {
        val startedAt = System.nanoTime()
        return try {
            delegate.getConnection(username, password)
        } finally {
            waits.record(startedAt)
        }
    }

/**
 * getLogWriter은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getLogWriter(): PrintWriter? = delegate.logWriter

/**
 * setLogWriter은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun setLogWriter(out: PrintWriter?) {
        delegate.logWriter = out
    }

/**
 * setLoginTimeout은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun setLoginTimeout(seconds: Int) {
        delegate.loginTimeout = seconds
    }

/**
 * getLoginTimeout은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getLoginTimeout(): Int = delegate.loginTimeout

/**
 * getParentLogger은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getParentLogger(): Logger = delegate.parentLogger

/**
 * unwrap은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun <T> unwrap(iface: Class<T>): T = delegate.unwrap(iface)

/**
 * isWrapperFor은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)
}

/**
 * OneShotAcquisitionTimeoutDataSource 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class OneShotAcquisitionTimeoutDataSource(
/**
 * delegate은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val delegate: HikariDataSource,
/**
 * connectionsToHold은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val connectionsToHold: Int,
): DataSource {
/**
 * arm은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val arm = AtomicBoolean(true)
/**
 * holderDone은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val holderDone = CountDownLatch(1)
/**
 * holderFailure은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val holderFailure = AtomicReference<Throwable?>()

/**
 * getConnection은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getConnection(): Connection = decorate(delegate.connection)

/**
 * getConnection은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getConnection(username: String?, password: String?): Connection =
        decorate(delegate.getConnection(username, password))

    @Suppress("SwallowedException") // Reflective invocation must expose the JDBC cause, not its wrapper.
/**
 * decorate은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun decorate(connection: Connection): Connection {
        if (!arm.compareAndSet(true, false)) return connection
        val trigger = AtomicBoolean(true)

        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            try {
                val result = method.invoke(connection, *arguments.orEmpty())
                if (method.name == "close" && trigger.compareAndSet(true, false)) holdEntirePool()
                result
            } catch (failure: InvocationTargetException) {
                throw checkNotNull(failure.cause)
            }
        } as Connection
    }

/**
 * holdEntirePool은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun holdEntirePool() {
        val acquired = CountDownLatch(1)

        Thread.ofVirtual().name("voucher-timeout-holder").start {
            val held = ArrayList<Connection>(connectionsToHold)
            try {
                repeat(connectionsToHold) { held += delegate.connection }
                acquired.countDown()
                Thread.sleep(POOL_HOLD_MILLIS)
            } catch (failure: Throwable) {
                holderFailure.set(failure)
                acquired.countDown()
            } finally {
                held.forEach(Connection::close)
                holderDone.countDown()
            }
        }
        check(acquired.await(HOLDER_START_SECONDS.seconds)) { "timeout holder did not acquire the pool" }
        holderFailure.get()?.let { throw IllegalStateException("timeout holder failed", it) }
    }

/**
 * awaitHolder은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun awaitHolder() {
        check(holderDone.await(HOLDER_STOP_SECONDS.seconds)) { "timeout holder did not release the pool" }
        holderFailure.get()?.let { throw IllegalStateException("timeout holder failed", it) }
    }

/**
 * getLogWriter은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getLogWriter(): PrintWriter? = delegate.logWriter
/**
 * setLogWriter은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun setLogWriter(out: PrintWriter?) {
        delegate.logWriter = out
    }

/**
 * setLoginTimeout은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun setLoginTimeout(seconds: Int) {
        delegate.loginTimeout = seconds
    }

/**
 * getLoginTimeout은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getLoginTimeout(): Int = delegate.loginTimeout
/**
 * getParentLogger은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getParentLogger(): Logger = delegate.parentLogger
/**
 * unwrap은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun <T> unwrap(iface: Class<T>): T = delegate.unwrap(iface)
/**
 * isWrapperFor은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)

    companion object: KLogging() {
/**
 * POOL_HOLD_MILLIS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val POOL_HOLD_MILLIS = 800L
/**
 * HOLDER_START_SECONDS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val HOLDER_START_SECONDS = 2L
/**
 * HOLDER_STOP_SECONDS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val HOLDER_STOP_SECONDS = 2L
    }
}

private fun kotlin.time.Duration.ceilingMillis(): Long =
    inWholeNanoseconds.ceilingMillisFromNanos()

/**
 * ceilingMillisFromNanos은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun Long.ceilingMillisFromNanos(): Long =
    this / NANOS_PER_MILLI + if (this % NANOS_PER_MILLI == 0L) 0L else 1L

/**
 * NANOS_PER_MILLI은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * StressEvidenceWriter 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class StressEvidenceWriter(private val outputRoot: Path) {

    companion object: KLogging() {
/**
 * manifests은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val manifests = ConcurrentHashMap<String, ConcurrentHashMap<String, ArtifactEntry>>()
    }

/**
 * write은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun write(evidence: VoucherPoolStressEvidence, threadDump: String) {
        val runDirectory = outputRoot.resolve(evidence.runId)
        Files.createDirectories(runDirectory)
        val profile = "${evidence.clients}-${evidence.redisMode.name.lowercase()}"
        val json = runDirectory.resolve("$profile.json")
        val dump = runDirectory.resolve("$profile.threads.txt")
        Files.writeString(json, evidence.toJson(), Charsets.UTF_8)
        Files.writeString(dump, threadDump, Charsets.UTF_8)

        val entry = ArtifactEntry(
            profile = profile,
            evidence = json.fileName.toString(),
            evidenceSha256 = sha256(json),
            threadDump = dump.fileName.toString(),
            threadDumpSha256 = sha256(dump)
        )
        val entries = manifests.computeIfAbsent(evidence.runId) { ConcurrentHashMap() }
        entries[profile] = entry

        writeManifest(
            runDirectory = runDirectory,
            runId = evidence.runId,
            entries = entries.values.sortedBy { it.profile }
        )
    }

/**
 * writeManifest은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun writeManifest(runDirectory: Path, runId: String, entries: List<ArtifactEntry>) {
        val body = buildString {
            append("{\n  \"runId\": \"").append(runId.json()).append("\",\n  \"profiles\": [\n")
            entries.forEachIndexed { index, entry ->
                append("    {\"profile\":\"").append(entry.profile).append("\",\"evidence\":\"").append(entry.evidence)
                    .append("\",\"evidenceSha256\":\"").append(entry.evidenceSha256).append("\",\"threadDump\":\"")
                    .append(entry.threadDump).append("\",\"threadDumpSha256\":\"").append(entry.threadDumpSha256)
                    .append("\"}")
                if (index < entries.lastIndex) append(',')
                appendLine()
            }
            append("  ]\n}\n")
        }
        Files.writeString(runDirectory.resolve("manifest.json"), body, Charsets.UTF_8)
    }

/**
 * toJson은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun VoucherPoolStressEvidence.toJson(): String =
        javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .joinToString(prefix = "{\n", postfix = "\n}\n", separator = ",\n") { field ->
                field.trySetAccessible()
/**
 * value은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val value = field.get(this)
/**
 * encoded은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val encoded = if (value is Number) value.toString() else "\"${value.toString().json()}\""
                "  \"${field.name}\": $encoded"
            }

/**
 * sha256은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

/**
 * json은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun String.json(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * ArtifactEntry 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class ArtifactEntry(
/**
 * profile은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val profile: String,
/**
 * evidence은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val evidence: String,
/**
 * evidenceSha256은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val evidenceSha256: String,
/**
 * threadDump은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val threadDump: String,
/**
 * threadDumpSha256은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val threadDumpSha256: String,
    )
}

/**
 * stressThreadDump은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun stressThreadDump(profile: String, loadThreads: Set<Thread>): String = buildString {
    append("profile=").append(profile).appendLine()
    append("stressTasks=").append(loadThreads.size).appendLine()

    loadThreads.sortedBy(Thread::threadId).forEach { thread ->
        append("stress-task id=")
        append(thread.threadId())
        append(" virtual=")
        append(thread.isVirtual)
        append(" state=")
        append(thread.state)
        appendLine()
        thread.stackTrace.take(12).forEach { frame ->
            append("  at ").append(frame).appendLine()
        }
    }

    Thread.getAllStackTraces().entries.sortedBy { it.key.name }.take(256).forEach { (thread, frames) ->
        append('"').append(thread.name).append("\" state=").append(thread.state).appendLine()
        frames.take(12).forEach { frame ->
            append("  at ").append(frame).appendLine()
        }
    }
}
