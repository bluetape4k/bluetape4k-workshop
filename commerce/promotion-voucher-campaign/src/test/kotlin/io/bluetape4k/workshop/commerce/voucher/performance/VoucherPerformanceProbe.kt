package io.bluetape4k.workshop.commerce.voucher.performance

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.math.ceil

/**
 * VoucherStressEvidence 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class VoucherStressEvidence(
/**
 * jsonPath은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val jsonPath: Path,
/**
 * jfrPath은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val jfrPath: Path,
/**
 * hikariActiveMax은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val hikariActiveMax: Int,
/**
 * databasePermitMax은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val databasePermitMax: Int,
/**
 * deterministicLockTimeoutContractPassed은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deterministicLockTimeoutContractPassed: Boolean,
/**
 * resourceLeaks은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val resourceLeaks: Int,
/**
 * capacityInvariant은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val capacityInvariant: Boolean,
)

/** repository code를 바꾸지 않고 JDBC connection과 statement execution boundary를 계산합니다. */
/** repository code를 바꾸지 않고 JDBC connection과 statement execution boundary를 계산합니다. */
@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
internal class VoucherJdbcProbeDataSource(
/**
 * delegate은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val delegate: DataSource,
): DataSource by delegate {

    private companion object: KLogging() {
/**
 * STATEMENT_FACTORIES은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val STATEMENT_FACTORIES = setOf(
            "createStatement",
            "prepareStatement",
            "prepareCall"
        )
/**
 * EXECUTION_METHODS은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val EXECUTION_METHODS = setOf(
            "execute",
            "executeQuery",
            "executeUpdate",
            "executeBatch",
            "executeLargeBatch",
            "executeLargeUpdate"
        )
    }

/**
 * connectionAcquisitions은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val connectionAcquisitions = AtomicLong()
/**
 * statementExecutions은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val statementExecutions = AtomicLong()

/**
 * getConnection은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getConnection(): Connection = observed(delegate.connection)

/**
 * getConnection은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun getConnection(
        username: String,
        password: String,
    ): Connection =
        observed(delegate.getConnection(username, password))

/**
 * connectionAcquisitions은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun connectionAcquisitions(): Long = connectionAcquisitions.get()

/**
 * statementExecutions은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun statementExecutions(): Long = statementExecutions.get()

/**
 * observed은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun observed(connection: Connection): Connection {
        connectionAcquisitions.incrementAndGet()
        return proxy(connection, Connection::class.java) { method, result ->
            if (method.name in STATEMENT_FACTORIES && result is Statement) observed(result) else result
        }
    }

/**
 * observed은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun observed(statement: Statement): Statement =
        proxy(statement, statementContract(statement)) { method, result ->
            if (method.name in EXECUTION_METHODS) statementExecutions.incrementAndGet()
            result
        }

    @Suppress("UNCHECKED_CAST")
/**
 * proxy은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun <T: Any> proxy(
        target: T,
        contract: Class<*>,
        afterInvocation: (Method, Any?) -> Any?,
    ): T =
        Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, arguments ->
/**
 * result은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val result = invoke(target, method, arguments)
            afterInvocation(method, result)
        } as T

/**
 * statementContract은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun statementContract(statement: Statement): Class<out Statement> =
        when (statement) {
            is CallableStatement -> CallableStatement::class.java
            is PreparedStatement -> PreparedStatement::class.java
            else -> Statement::class.java
        }

/**
 * invoke은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun invoke(
        target: Any,
        method: Method,
        arguments: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(arguments ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

}

/**
 * 단일 profile에 대해 bounded pool, permit, PostgreSQL, Lettuce, allocation, GC evidence를 sampling합니다.
 *
 * latency 값은 의도적으로 report-only입니다. correctness gate는 machine-dependent wall-clock threshold 대신
 * capacity, permit, leak, deterministic lock-timeout contract를 사용합니다.
 */
internal class VoucherPerformanceProbe(
/**
 * reportDirectory은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val reportDirectory: Path,
/**
 * profile은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val profile: String,
/**
 * concurrency은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val concurrency: Int,
/**
 * redisMode은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redisMode: String,
/**
 * seed은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val seed: Long,
/**
 * containerImage은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val containerImage: String,
/**
 * hikari은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val hikari: HikariDataSource,
/**
 * databasePermits은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val databasePermits: DatabasePermitGate,
/**
 * jdbc은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val jdbc: VoucherJdbcProbeDataSource,
/**
 * pgStatDataSource은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val pgStatDataSource: DataSource,
/**
 * applicationName은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val applicationName: String,
): AutoCloseable {

    companion object: KLogging() {
/**
 * SAMPLE_INTERVAL은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val SAMPLE_INTERVAL = 10L
/**
 * EXPECTED_STATUSES은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val EXPECTED_STATUSES = listOf(200, 201, 409, 429, 503)
    }

/**
 * running은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val running = AtomicBoolean(true)
/**
 * finished은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val finished = AtomicBoolean()
/**
 * samples은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val samples = ConcurrentLinkedQueue<OperationSample>()
/**
 * pgSamples은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val pgSamples = ConcurrentLinkedQueue<PgActivitySample>()
/**
 * hikariActiveMax은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val hikariActiveMax = AtomicInteger()
/**
 * hikariIdleMax은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val hikariIdleMax = AtomicInteger()
/**
 * hikariPendingMax은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val hikariPendingMax = AtomicInteger()
/**
 * permitMax은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val permitMax = AtomicInteger()
/**
 * laneActiveMax은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val laneActiveMax = DatabaseLane.entries.associateWith { AtomicInteger() }
/**
 * laneWaitMax은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val laneWaitMax = DatabaseLane.entries.associateWith { AtomicInteger() }
/**
 * redisCommands은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redisCommands = AtomicLong()
/**
 * redisSucceeded은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redisSucceeded = AtomicLong()
/**
 * redisFailed은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redisFailed = AtomicLong()
/**
 * redisLatencyNanos은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redisLatencyNanos = AtomicLong()
/**
 * startedAt은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val startedAt = System.nanoTime()
/**
 * jfrPath은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val jfrPath = reportDirectory.resolve("$profile.jfr")
/**
 * jsonPath은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val jsonPath = reportDirectory.resolve("$profile.json")
/**
 * recording은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val recording = createRecording()
/**
 * sampler은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val sampler = Thread.ofPlatform().name("voucher-stress-probe-$profile").start {
        while (running.get()) {
            sampleRuntime()
            try {
                Thread.sleep(SAMPLE_INTERVAL)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    init {
        Files.createDirectories(reportDirectory)
        recording.start()
    }

/**
 * recordOperation은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun recordOperation(
        operation: String,
        status: Int,
        latencyNanos: Long,
    ) {
        samples += OperationSample(operation, status, latencyNanos.coerceAtLeast(0))
    }

/**
 * recordRedisCommand은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun recordRedisCommand(
        succeeded: Boolean,
        latencyNanos: Long,
    ) {
        redisCommands.incrementAndGet()
        if (succeeded) redisSucceeded.incrementAndGet() else redisFailed.incrementAndGet()
        redisLatencyNanos.addAndGet(latencyNanos.coerceAtLeast(0))
    }

/**
 * finish은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun finish(
        expectedOperations: Map<String, Int>,
        capacityInvariant: Boolean,
        deterministicLockTimeoutContractPassed: Boolean,
    ): VoucherStressEvidence {
        check(finished.compareAndSet(false, true)) {
            "performance evidence was already finalized"
        }
        stopSampling()
        recording.stop()
        recording.dump(jfrPath)
        recording.close()

/**
 * jfr은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val jfr = readJfr(jfrPath)
/**
 * operationCounts은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val operationCounts = samples.groupingBy(OperationSample::operation).eachCount().toSortedMap()

        check(operationCounts == expectedOperations) {
            "operation matrix mismatch: expected=$expectedOperations actual=$operationCounts"
        }
/**
 * statusCounts은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val statusCounts = (EXPECTED_STATUSES + samples.map { it.status })
            .associateWith { status -> samples.count { it.status == status } }
            .mapKeys { (status, _) -> status.toString() }
            .toSortedMap()
/**
 * latencies은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val latencies = samples.map(OperationSample::latencyNanos).sorted()
/**
 * elapsedNanos은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val elapsedNanos = (System.nanoTime() - startedAt).coerceAtLeast(1)
/**
 * pool은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val pool = hikari.hikariPoolMXBean
/**
 * resourceLeaks은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val resourceLeaks = databasePermits.inUsePermits() + pool.activeConnections
/**
 * maxConsecutiveWaitSamples은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val maxConsecutiveWaitSamples = maxConsecutiveWaitSamples(pgSamples.toList())

/**
 * evidence은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val evidence = VoucherStressEvidence(
            jsonPath = jsonPath,
            jfrPath = jfrPath,
            hikariActiveMax = hikariActiveMax.get(),
            databasePermitMax = permitMax.get(),
            deterministicLockTimeoutContractPassed = deterministicLockTimeoutContractPassed,
            resourceLeaks = resourceLeaks,
            capacityInvariant = capacityInvariant,
        )
/**
 * document은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val document = linkedMapOf<String, Any>(
            "schemaVersion" to "1.0",
            "gitSha" to gitSha(),
            "environment" to mapOf(
                "javaVersion" to System.getProperty("java.version"),
                "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
                "cpuCount" to Runtime.getRuntime().availableProcessors(),
                "containerImage" to containerImage,
            ),
            "seed" to seed,
            "profile" to profile,
            "configuration" to mapOf(
                "concurrency" to concurrency,
                "redisMode" to redisMode,
                "hotspot" to true,
                "sampleIntervalMillis" to SAMPLE_INTERVAL,
            ),
            "operationCounts" to operationCounts,
            "statusCounts" to statusCounts,
            "latencyNanos" to
                    mapOf(
                        "p50" to percentile(latencies, 0.50),
                        "p95" to percentile(latencies, 0.95),
                        "p99" to percentile(latencies, 0.99),
                    ),
            "throughputPerSecond" to samples.size * TimeUnit.SECONDS.toNanos(1).toDouble() / elapsedNanos,
            "maxGauges" to mapOf(
                "hikariActive" to hikariActiveMax.get(),
                "hikariIdle" to hikariIdleMax.get(),
                "hikariPending" to hikariPendingMax.get(),
                "databasePermits" to permitMax.get(),
                "laneInUse" to laneActiveMax.mapKeys { it.key.name }.mapValues { it.value.get() },
                "laneWaiting" to laneWaitMax.mapKeys { it.key.name }.mapValues { it.value.get() },
            ),
            "roundTrips" to mapOf(
                "postgresTransactions" to jdbc.connectionAcquisitions(),
                "postgresStatements" to jdbc.statementExecutions(),
                "redisCommands" to redisCommands.get(),
                "redisSucceeded" to redisSucceeded.get(),
                "redisFailed" to redisFailed.get(),
                "redisLatencyNanos" to redisLatencyNanos.get(),
            ),
            "allocation" to mapOf(
                "sampledBytes" to jfr.allocationBytes,
                "bytesPerOperation" to jfr.allocationBytes.toDouble() / samples.size.coerceAtLeast(1),
            ),
            "gc" to mapOf("pauseCount" to jfr.gcPauseCount, "pauseNanos" to jfr.gcPauseNanos),
            "jfrEvents" to mapOf("threadPark" to jfr.threadParks, "monitorEnter" to jfr.monitorEnters),
            "pgStatActivity" to mapOf(
                "sampleCount" to pgSamples.size,
                "maxConsecutiveWaitSamples" to maxConsecutiveWaitSamples,
                "samples" to pgSamples.toList(),
            ),
            "correctness" to mapOf(
                "capacityInvariant" to capacityInvariant,
                "deterministicLockTimeoutContractPassed" to deterministicLockTimeoutContractPassed,
                "resourceLeaks" to resourceLeaks,
            ),
            "artifacts" to mapOf("json" to jsonPath.fileName.toString(), "jfr" to jfrPath.fileName.toString()),
            "probeSources" to listOf(
                "HikariPoolMXBean",
                "DatabasePermitGate fair semaphore lane probes",
                "VoucherJdbcProbeDataSource JDBC statement interceptor",
                "Instrumented Lettuce-backed RateLimiter command boundary",
                "PostgreSQL pg_stat_activity 10ms samples",
                "JDK Flight Recorder allocation, park, monitor, and GC events",
                "VoucherConcurrencyIntegrationTest deterministic SET LOCAL lock_timeout contract",
            ),
        )
        Jackson.defaultJsonMapper.writeValue(jsonPath.toFile(), document)
        log.info {
            "voucher_stress_evidence_written profile=$profile operations=${samples.size} " +
                    "hikariActiveMax=${evidence.hikariActiveMax} permitMax=${evidence.databasePermitMax}"
        }
        return evidence
    }

/**
 * close은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        if (finished.compareAndSet(false, true)) {
            stopSampling()
            runCatching { recording.stop() }
            recording.close()
        }
    }

/**
 * sampleRuntime은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sampleRuntime() {
        val pool = hikari.hikariPoolMXBean ?: return
        hikariActiveMax.accumulateAndGet(pool.activeConnections, ::maxOf)
        hikariIdleMax.accumulateAndGet(pool.idleConnections, ::maxOf)
        hikariPendingMax.accumulateAndGet(pool.threadsAwaitingConnection, ::maxOf)
        val laneActive = DatabaseLane.entries.sumOf(databasePermits::inUsePermits)
        permitMax.accumulateAndGet(laneActive, ::maxOf)

        DatabaseLane.entries.forEach { lane ->
            laneActiveMax.getValue(lane).accumulateAndGet(databasePermits.inUsePermits(lane), ::maxOf)
            laneWaitMax.getValue(lane).accumulateAndGet(databasePermits.waitingThreads(lane), ::maxOf)
        }
        samplePgActivity()
    }

/**
 * samplePgActivity은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun samplePgActivity() {
        val waits = mutableListOf<String>()
        try {
            pgStatDataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    SELECT coalesce(wait_event_type, '') || ':' || coalesce(wait_event, '')
                      FROM pg_stat_activity
                     WHERE application_name = ?
                       AND pid <> pg_backend_pid()
                       AND state <> 'idle'
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, applicationName)
                    stmt.executeQuery().use { result ->
                        while (result.next()) {
                            waits += result.getString(1)
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            waits += "probe-error:${failure.javaClass.simpleName}"
        }

        pgSamples += PgActivitySample(Instant.now().toString(), waits.sorted())
    }

/**
 * stopSampling은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun stopSampling() {
        running.set(false)
        sampler.interrupt()
        sampler.join(5.seconds())
        sampleRuntime()
    }

/**
 * createRecording은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createRecording(): Recording =
        Recording().apply {
            name = "voucher-stress-$profile"
            enable("jdk.ObjectAllocationSample")
            enable("jdk.ThreadPark").withThreshold(Duration.ZERO)
            enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO)
            enable("jdk.GarbageCollection")
            enable("jdk.GCPhasePause").withThreshold(Duration.ZERO)
        }

/**
 * readJfr은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun readJfr(path: Path): JfrEvidence {
        var allocationBytes = 0L
        var gcPauseCount = 0L
        var gcPauseNanos = 0L
        var threadParks = 0L
        var monitorEnters = 0L

        RecordingFile(path).use { recordingFile ->
            while (recordingFile.hasMoreEvents()) {
                val event = recordingFile.readEvent()
                when (event.eventType.name) {
                    "jdk.ObjectAllocationSample" -> allocationBytes += event.getLong("weight")
                    "jdk.GarbageCollection", "jdk.GCPhasePause" -> {
                        gcPauseCount++
                        gcPauseNanos += event.duration.toNanos()
                    }
                    "jdk.ThreadPark" -> threadParks++
                    "jdk.JavaMonitorEnter" -> monitorEnters++
                }
            }
        }
        return JfrEvidence(
            allocationBytes = allocationBytes,
            gcPauseCount = gcPauseCount,
            gcPauseNanos = gcPauseNanos,
            threadParks = threadParks,
            monitorEnters = monitorEnters
        )
    }

/**
 * percentile은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun percentile(
        values: List<Long>,
        percentile: Double,
    ): Long {
        if (values.isEmpty()) return 0
/**
 * index은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val index = (ceil(values.size * percentile).toInt() - 1).coerceIn(values.indices)

        return values[index]
    }

/**
 * maxConsecutiveWaitSamples은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun maxConsecutiveWaitSamples(samples: List<PgActivitySample>): Int {
        var current = 0
        var maximum = 0
        samples.forEach { sample ->
            current = if (sample.waits.any { it.isNotBlank() && !it.startsWith(":") }) current + 1 else 0
            maximum = maxOf(maximum, current)
        }
        return maximum
    }

/**
 * gitSha은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun gitSha(): String =
        runCatching {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start()
                .run {
                    check(waitFor(5.seconds()) && exitValue() == 0)
                    inputStream.bufferedReader().use { it.readText().trim() }
                }
        }.getOrElse { "unknown" }

/**
 * OperationSample 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class OperationSample(val operation: String, val status: Int, val latencyNanos: Long)

/**
 * PgActivitySample 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class PgActivitySample(val timestamp: String, val waits: List<String>)

/**
 * JfrEvidence 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class JfrEvidence(
/**
 * allocationBytes은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val allocationBytes: Long,
/**
 * gcPauseCount은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val gcPauseCount: Long,
/**
 * gcPauseNanos은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val gcPauseNanos: Long,
/**
 * threadParks은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val threadParks: Long,
/**
 * monitorEnters은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val monitorEnters: Long,
    )

}
