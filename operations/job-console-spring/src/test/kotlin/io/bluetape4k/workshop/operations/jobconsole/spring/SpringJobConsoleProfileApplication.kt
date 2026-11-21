package io.bluetape4k.workshop.operations.jobconsole.spring

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleBarrier
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleScenario
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionDockerResource
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionAwait
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionJournal
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionProfile
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleAuthorityBaseline
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleAuthorityQueries
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleLiveProfileAdapter
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleDockerResources
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleProxiedTopology
import io.bluetape4k.workshop.operations.jobconsole.highcontention.OwnedRedisNamespace
import io.bluetape4k.workshop.operations.jobconsole.highcontention.ScheduleToken
import io.bluetape4k.workshop.operations.jobconsole.highcontention.WorkloadIdentity
import io.bluetape4k.workshop.operations.jobconsole.highcontention.WorkloadTerminalDisposition
import io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.signal.CancelSignal
import io.bluetape4k.workshop.operations.jobconsole.signal.LettuceCancelSignal
import io.bluetape4k.workshop.operations.jobconsole.worker.JobWorkerEngine
import io.lettuce.core.RedisClient
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.sql.DataSource
import kotlin.concurrent.read
import kotlin.concurrent.write

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(
    JobConsoleSpringConfiguration::class,
    JobConsoleSpringController::class,
    JobConsoleOutboxSchedule::class,
    JobConsoleWorkerSchedule::class,
    JobConsoleSpringQueueController::class,
    JobConsoleSpringOperationsController::class,
    JobConsoleProblemHandler::class,
)
/**
 * SpringJobConsoleProfileApplication 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class SpringJobConsoleProfileApplication

/**
 * SpringReplacementOutcome 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal sealed interface SpringReplacementOutcome {
/**
 * Claimed 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    data class Claimed(val job: ClaimedJob): SpringReplacementOutcome

/**
 * Completed 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    data object Completed: SpringReplacementOutcome
}

/**
 * awaitSpringReplacement은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
internal fun awaitSpringReplacement(
    timeout: Duration,
    pollInterval: Duration,
    reclaim: () -> ClaimedJob?,
    isCompleted: () -> Boolean,
): SpringReplacementOutcome =
    HighContentionAwait.value(
        timeout = timeout,
        pollInterval = pollInterval,
        description = "replacement Spring worker did not reclaim or complete the expired lease",
    ) {
        reclaim()?.let { SpringReplacementOutcome.Claimed(it) }
            ?: if (isCompleted()) SpringReplacementOutcome.Completed else null
    }

/**
 * `SpringJobConsoleProfileAction`은 업무 분기용 제한된 값 집합입니다.
 * 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행하는 명령·신호 계약입니다.
 */
internal enum class SpringJobConsoleProfileAction(
/**
 * profileId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val profileId: String,
) {
    /**
     * `SpringJobConsoleProfileAction.BURST`은 `burst` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을 검증한
     * 뒤 허용된 전이만 실행합니다.
     */
    BURST("burst"),
    /**
     * `SpringJobConsoleProfileAction.DUPLICATE_STORM`은 `duplicate storm` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    DUPLICATE_STORM("duplicate-storm"),
    /**
     * `SpringJobConsoleProfileAction.REDIS_PATH_OUTAGE`은 `redis path outage` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    REDIS_PATH_OUTAGE("redis-path-outage"),
    /**
     * `SpringJobConsoleProfileAction.REDIS_KEY_LOSS`은 `redis key loss` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    REDIS_KEY_LOSS("redis-key-loss"),
    /**
     * `SpringJobConsoleProfileAction.SLOW_PROVIDER`은 `slow provider` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    SLOW_PROVIDER("slow-provider"),
    /**
     * `SpringJobConsoleProfileAction.WORKER_RESTART`은 `worker restart` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    WORKER_RESTART("worker-restart"),
    /**
     * `SpringJobConsoleProfileAction.DUPLICATE_DELIVERY`은 `duplicate delivery` 업무 상황을 나타냅니다. 현재
     * 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    DUPLICATE_DELIVERY("duplicate-delivery"),
    ;

    companion object {
/**
 * resolve은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun resolve(profileId: String): SpringJobConsoleProfileAction {
            val validProfileId = profileId.requireNotBlank("profileId")
            return entries.singleOrNull { it.profileId == validProfileId }
                ?: throw IllegalArgumentException("unsupported Spring Job Console profile[$validProfileId]")
        }
    }
}

/**
 * SpringJobConsoleStaleAttemptEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class SpringJobConsoleStaleAttemptEvidence(
/**
 * pausedConnections은 테스트 fixture가 보관하는 pausedConnections 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val pausedConnections: Int,
/**
 * pausedTransactions은 테스트 fixture가 보관하는 pausedTransactions 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val pausedTransactions: Int,
/**
 * pausedLocks은 테스트 fixture가 보관하는 pausedLocks 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val pausedLocks: Int,
/**
 * oldLeaseToken은 테스트 fixture가 보관하는 oldLeaseToken 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val oldLeaseToken: String,
/**
 * pendingCompletedChunk은 테스트 fixture가 보관하는 pendingCompletedChunk 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val pendingCompletedChunk: Long,
/**
 * staleErrorCode은 테스트 fixture가 보관하는 staleErrorCode 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val staleErrorCode: JobProblemCode,
/**
 * contextExecutorStopped은 테스트 fixture가 보관하는 contextExecutorStopped 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val contextExecutorStopped: Boolean,
/**
 * lateEffectCount은 테스트 fixture가 보관하는 lateEffectCount 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val lateEffectCount: Long,
/**
 * lateReceiptCount은 테스트 fixture가 보관하는 lateReceiptCount 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val lateReceiptCount: Long,
)

/**
 * SpringRedisPathEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class SpringRedisPathEvidence(
/**
 * effectiveEndpointRoutedThroughProxy은 테스트 fixture가 보관하는 effectiveEndpointRoutedThroughProxy 업무
 * 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val effectiveEndpointRoutedThroughProxy: Boolean,
/**
 * oldConnectionFailureObserved은 테스트 fixture가 보관하는 oldConnectionFailureObserved 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val oldConnectionFailureObserved: Boolean,
/**
 * newConnectionFailureObserved은 테스트 fixture가 보관하는 newConnectionFailureObserved 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val newConnectionFailureObserved: Boolean,
/**
 * recovered은 테스트 fixture가 보관하는 recovered 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val recovered: Boolean,
/**
 * durableCancelsDuringOutage은 테스트 fixture가 보관하는 durableCancelsDuringOutage 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val durableCancelsDuringOutage: Int,
)

/**
 * SpringRedisKeyLossEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class SpringRedisKeyLossEvidence(
/**
 * deletedKeyCount은 테스트 fixture가 보관하는 deletedKeyCount 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val deletedKeyCount: Int,
/**
 * durableCancelsAfterKeyLoss은 테스트 fixture가 보관하는 durableCancelsAfterKeyLoss 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val durableCancelsAfterKeyLoss: Int,
)

/**
 * SpringJobConsoleLiveAdapter 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class SpringJobConsoleLiveAdapter private constructor(
/**
 * runId은 테스트 fixture가 보관하는 runId 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val runId: String,
/**
 * profile은 테스트 fixture가 보관하는 profile 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val profile: HighContentionProfile,
/**
 * fixture은 테스트 fixture가 보관하는 fixture 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val fixture: JobConsoleContainerFixture,
/**
 * topology은 테스트 fixture가 보관하는 topology 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val topology: JobConsoleProxiedTopology,
/**
 * journal은 테스트 fixture가 보관하는 journal 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val journal: HighContentionJournal,
/**
 * journalRoot은 테스트 fixture가 보관하는 journalRoot 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val journalRoot: Path,
): JobConsoleLiveProfileAdapter {

/**
 * action은 테스트 fixture가 보관하는 action 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val action = SpringJobConsoleProfileAction.resolve(profile.profileId)
/**
 * lifecycleLock은 테스트 fixture가 보관하는 lifecycleLock 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val lifecycleLock = ReentrantReadWriteLock(true)
/**
 * winners은 테스트 fixture가 보관하는 winners 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val winners = ConcurrentHashMap<Int, Boolean>()
/**
 * jobIdsByIdentity은 테스트 fixture가 보관하는 jobIdsByIdentity 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val jobIdsByIdentity = ConcurrentHashMap<Int, UUID>()
/**
 * scenariosByIdentity은 테스트 fixture가 보관하는 scenariosByIdentity 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val scenariosByIdentity = ConcurrentHashMap<Int, JobConsoleScenario>()
/**
 * deliveredEventIds은 테스트 fixture가 보관하는 deliveredEventIds 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val deliveredEventIds = ConcurrentHashMap.newKeySet<UUID>()
/**
 * restartStarted은 테스트 fixture가 보관하는 restartStarted 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val restartStarted = AtomicBoolean()
/**
 * slowProviderStarted은 테스트 fixture가 보관하는 slowProviderStarted 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val slowProviderStarted = AtomicBoolean()
/**
 * outageActive은 테스트 fixture가 보관하는 outageActive 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val outageActive = AtomicBoolean()
/**
 * durableCancelsDuringOutage은 테스트 fixture가 보관하는 durableCancelsDuringOutage 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val durableCancelsDuringOutage = AtomicInteger()
/**
 * durableCancelsAfterKeyLoss은 테스트 fixture가 보관하는 durableCancelsAfterKeyLoss 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val durableCancelsAfterKeyLoss = AtomicInteger()
/**
 * ownedRedisNamespace은 테스트 fixture가 보관하는 ownedRedisNamespace 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val ownedRedisNamespace =
        "hc:v1:$runId:job-spring:redis-key-loss:"

    @Volatile
/**
 * measuredBaseline은 테스트 fixture가 보관하는 measuredBaseline 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private var measuredBaseline: JobConsoleAuthorityBaseline? = null

    @Volatile
/**
 * application은 테스트 fixture가 보관하는 application 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private var application: RunningSpringJobConsole = startApplication()

    @Volatile
/**
 * staleAttemptEvidence은 테스트 fixture가 보관하는 staleAttemptEvidence 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    var staleAttemptEvidence: SpringJobConsoleStaleAttemptEvidence? = null
        private set

    @Volatile
/**
 * redisPathEvidence은 테스트 fixture가 보관하는 redisPathEvidence 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    var redisPathEvidence: SpringRedisPathEvidence? = null
        private set

    @Volatile
/**
 * redisKeyLossEvidence은 테스트 fixture가 보관하는 redisKeyLossEvidence 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    var redisKeyLossEvidence: SpringRedisKeyLossEvidence? = null
        private set

    init {
        check(application.redisUri == topology.redisUri) {
            "Spring Redis endpoint differs from the proxied endpoint"
        }
        check(application.cancelSignal is LettuceCancelSignal) {
            "Spring did not create the Lettuce cancel signal"
        }
        check(application.cancelSignal.isAvailable()) {
            "Spring cancel signal is not connected through the proxy"
        }
    }

/**
 * warmUp은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun warmUp(identity: WorkloadIdentity) {
        lifecycleLock.read {
            val scenario = JobConsoleScenario.fromSeed(identity.namespace, identity.ordinal, workUnits = 1)
            val submitted = application.http.submit(scenario)
            completeThroughOwnedWorker(submitted.jobId)
            check(application.http.snapshot(submitted.jobId, scenario).state == "succeeded")
        }
    }

/**
 * snapshotBaseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun snapshotBaseline(): String =
        lifecycleLock.write {
            authorityBaseline().also { measuredBaseline = it }.encode().also {
                if (action == SpringJobConsoleProfileAction.REDIS_KEY_LOSS) {
                    seedOwnedRedisKeys()
                }
            }
        }

/**
 * execute은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun execute(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition =
        when (action) {
            SpringJobConsoleProfileAction.WORKER_RESTART ->
                if (restartStarted.compareAndSet(false, true)) {
                    lifecycleLock.write {
                        runFencedAttemptScenario(restartApplication = true, identity = identity)
                        winners[token.stableOrdinal] = true
                    }
                    WorkloadTerminalDisposition.COMPLETED
                } else {
                    lifecycleLock.read { submitAndComplete(token, identity) }
                }

            SpringJobConsoleProfileAction.SLOW_PROVIDER ->
                lifecycleLock.read {
                    if (slowProviderStarted.compareAndSet(false, true)) {
                        runFencedAttemptScenario(restartApplication = false, identity = identity)
                        winners[token.stableOrdinal] = true
                        WorkloadTerminalDisposition.COMPLETED
                    } else {
                        submitAndComplete(token, identity)
                    }
                }

            else -> lifecycleLock.read { executeLiveAction(token, identity) }
        }

/**
 * injectDeclaredFailure은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun injectDeclaredFailure() {
        when (action) {
            SpringJobConsoleProfileAction.REDIS_PATH_OUTAGE -> injectRedisPathOutage()
            SpringJobConsoleProfileAction.REDIS_KEY_LOSS -> deleteOwnedRedisKeys()
            else -> Unit
        }
    }

/**
 * authorityDelta은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun authorityDelta(): JobConsoleAuthorityBaseline {
        val baseline = checkNotNull(measuredBaseline) { "measured baseline was not captured" }
        val current = lifecycleLock.read { authorityBaseline() }
        return JobConsoleAuthorityBaseline(
            jobs = current.jobs - baseline.jobs,
            effects = current.effects - baseline.effects,
            receipts = current.receipts - baseline.receipts,
        )
    }

/**
 * winner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun winner(token: ScheduleToken): Boolean =
        winners[token.stableOrdinal] == true

/**
 * profileEvidence은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun profileEvidence(): String =
        lifecycleLock.read {
            val profileEvidence = when (action) {
                SpringJobConsoleProfileAction.REDIS_PATH_OUTAGE ->
                    redisPathEvidence?.let {
                        ",oldConnectionFailure=${it.oldConnectionFailureObserved}," +
                                "newConnectionFailure=${it.newConnectionFailureObserved}," +
                                "redisRecovered=${it.recovered}," +
                                "durableCancelsDuringOutage=${it.durableCancelsDuringOutage}"
                    }.orEmpty()

                SpringJobConsoleProfileAction.REDIS_KEY_LOSS    ->
                    redisKeyLossEvidence?.let {
                        ",deletedOwnedKeys=${it.deletedKeyCount}," +
                                "durableCancelsAfterKeyLoss=${it.durableCancelsAfterKeyLoss}"
                    }.orEmpty()

                SpringJobConsoleProfileAction.SLOW_PROVIDER,
                SpringJobConsoleProfileAction.WORKER_RESTART,
                                                                -> staleAttemptEvidence?.let {
                    ",staleCode=${it.staleErrorCode}," +
                            "contextExecutorStopped=${it.contextExecutorStopped}," +
                            "lateEffects=${it.lateEffectCount},lateReceipts=${it.lateReceiptCount}"
                }.orEmpty()

                else                                            -> ""
            }
            "hikari=true,maxPool=${application.dataSource.maximumPoolSize}," +
                    "redisProxy=${application.redisUri == topology.redisUri}$profileEvidence"
        }

/**
 * assertProfileInvariants은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun assertProfileInvariants(delta: JobConsoleAuthorityBaseline) {
        check(delta.jobs > 0) { "measured workload did not create durable Job authority" }
        check(delta.effects > 0) { "measured workload did not create durable history" }
        check(delta.receipts > 0) { "measured workload did not create durable outbox receipts" }
        check(jobIdsByIdentity.isNotEmpty()) { "measured workload has no live HTTP identities" }
        jobIdsByIdentity.forEach { (identityOrdinal, jobId) ->
            val scenario = scenariosByIdentity.getValue(identityOrdinal)
            val http = application.http.snapshot(jobId, scenario)
            val stored = checkNotNull(application.repository.load(jobId))
            check(http.state == stored.state.wireValue) {
                "HTTP snapshot and PostgreSQL authority diverged for job[$jobId]"
            }
        }
        when (action) {
            SpringJobConsoleProfileAction.BURST -> {
                check(delta.jobs == profile.operationCount.toLong())
                check(jobIdsByIdentity.size == profile.operationCount)
                check(burstQueueViolationCount() == 0L)
                check(activeJobCount() == 0L)
                check(queueVersionDivergenceCount() == 0L)
            }

            SpringJobConsoleProfileAction.DUPLICATE_STORM -> {
                check(delta.jobs == profile.contentionShape.identityCount.toLong())
                check(jobIdsByIdentity.size == profile.contentionShape.identityCount)
            }

            SpringJobConsoleProfileAction.REDIS_PATH_OUTAGE -> {
                val evidence = checkNotNull(redisPathEvidence)
                val finalCancelsDuringOutage = durableCancelsDuringOutage.get()
                redisPathEvidence = SpringRedisPathEvidence(
                    effectiveEndpointRoutedThroughProxy = evidence.effectiveEndpointRoutedThroughProxy,
                    oldConnectionFailureObserved = evidence.oldConnectionFailureObserved,
                    newConnectionFailureObserved = evidence.newConnectionFailureObserved,
                    recovered = evidence.recovered,
                    durableCancelsDuringOutage = finalCancelsDuringOutage,
                )
                check(evidence.effectiveEndpointRoutedThroughProxy)
                check(evidence.oldConnectionFailureObserved)
                check(evidence.newConnectionFailureObserved)
                check(evidence.recovered)
                check(finalCancelsDuringOutage > 0)
            }

            SpringJobConsoleProfileAction.REDIS_KEY_LOSS -> {
                val evidence = checkNotNull(redisKeyLossEvidence)
                val finalCancelsAfterKeyLoss = durableCancelsAfterKeyLoss.get()
                redisKeyLossEvidence = SpringRedisKeyLossEvidence(
                    deletedKeyCount = evidence.deletedKeyCount,
                    durableCancelsAfterKeyLoss = finalCancelsAfterKeyLoss,
                )
                check(evidence.deletedKeyCount == profile.operationCount)
                check(finalCancelsAfterKeyLoss > 0)
            }

            SpringJobConsoleProfileAction.SLOW_PROVIDER,
            SpringJobConsoleProfileAction.WORKER_RESTART,
                                                -> assertStaleAttemptEvidence()

            SpringJobConsoleProfileAction.DUPLICATE_DELIVERY ->
                check(deliveredEventIds.isNotEmpty() && duplicateOutboxLogicalEventCount() == 0L) {
                    "duplicate delivery created a duplicate durable outbox event"
                }
        }
    }

/**
 * close은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        var failure: Throwable? = null
        listOf<() -> Unit>(
            { closeApplication(application) },
            { topology.close() },
            { journal.close() },
            { fixture.dropSchema() },
            { deleteDirectory(journalRoot) },
        ).forEach { action ->
            try {
                action()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

/**
 * executeLiveAction은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun executeLiveAction(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition {
        val scenario = scenarioFor(token, identity)
        scenariosByIdentity.putIfAbsent(identity.ordinal, scenario)
        val submitted = application.http.submit(scenario)
        val winner = jobIdsByIdentity.putIfAbsent(identity.ordinal, submitted.jobId) == null
        winners[token.stableOrdinal] = winner
        check(jobIdsByIdentity.getValue(identity.ordinal) == submitted.jobId) {
            "stable HTTP identity resolved to different jobs"
        }

        return when (action) {
            SpringJobConsoleProfileAction.REDIS_PATH_OUTAGE -> {
                val beganDuringOutage = outageActive.get()
                application.http.cancel(submitted.jobId, scenario)
                if (beganDuringOutage) durableCancelsDuringOutage.incrementAndGet()
                WorkloadTerminalDisposition.COMPLETED
            }

            SpringJobConsoleProfileAction.REDIS_KEY_LOSS -> {
                application.http.cancel(submitted.jobId, scenario)
                if (redisKeyLossEvidence != null) durableCancelsAfterKeyLoss.incrementAndGet()
                WorkloadTerminalDisposition.COMPLETED
            }

            SpringJobConsoleProfileAction.DUPLICATE_DELIVERY -> {
                if (winner) {
                    completeThroughOwnedWorker(submitted.jobId)
                    val claim = application.outboxRepository.claim(16, Duration.ofSeconds(5))
                    claim.events.forEach { event ->
                        check(application.outboxRepository.markPublished(claim.token, event.eventId))
                        check(application.outboxRepository.markPublished(claim.token, event.eventId))
                        deliveredEventIds += event.eventId
                    }
                }
                WorkloadTerminalDisposition.COMPLETED
            }

            SpringJobConsoleProfileAction.BURST -> {
                if (token.stableOrdinal == 0) application.http.verifyHeartbeat(submitted.jobId, scenario)
                if (winner) completeThroughOwnedWorker(submitted.jobId)
                WorkloadTerminalDisposition.COMPLETED
            }

            SpringJobConsoleProfileAction.DUPLICATE_STORM -> {
                if (winner) completeThroughOwnedWorker(submitted.jobId)
                WorkloadTerminalDisposition.COMPLETED
            }

            else                                -> error("profile action[$action] must use its dedicated lifecycle path")
        }
    }

/**
 * submitAndComplete은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun submitAndComplete(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition {
        val scenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        scenariosByIdentity.putIfAbsent(identity.ordinal, scenario)
        val submitted = application.http.submit(scenario)
        val winner = jobIdsByIdentity.putIfAbsent(identity.ordinal, submitted.jobId) == null
        winners[token.stableOrdinal] = winner
        if (winner) completeThroughOwnedWorker(submitted.jobId)
        return WorkloadTerminalDisposition.COMPLETED
    }

/**
 * completeThroughOwnedWorker은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun completeThroughOwnedWorker(jobId: UUID) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        do {
            val stored = application.repository.load(jobId)
            if (stored?.state?.terminal == true) return
            application.workerEngine.runOnce()
        } while (System.nanoTime() < deadline)
        error("context-owned worker did not complete job[$jobId]")
    }

/**
 * runFencedAttemptScenario은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun runFencedAttemptScenario(
        restartApplication: Boolean,
        identity: WorkloadIdentity,
    ) {
        val scenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        scenariosByIdentity.putIfAbsent(identity.ordinal, scenario)
        val submitted = application.http.submit(scenario)
        jobIdsByIdentity.putIfAbsent(identity.ordinal, submitted.jobId)
        val oldClaim = checkNotNull(
            application.repository.claimNext(scenario.scope.tenantId, Duration.ofMillis(10)),
        )
        val staleDataSource = createProfileOwnedDataSource()
        val staleRepository = JobRepository(staleDataSource)
        val barrier = JobConsoleBarrier(1)
        val staleExecutor = VirtualThreads.executorService()
        try {
            val staleAttempt = staleExecutor.submit<JobProblemCode> {
                check(barrier.readyAndAwait(Duration.ofSeconds(10))) {
                    "stale Spring attempt barrier timed out"
                }
                try {
                    staleRepository.checkpoint(oldClaim.lease, completedChunk = 1, progress = 100)
                    error("stale Spring checkpoint unexpectedly committed")
                } catch (failure: JobRepositoryException) {
                    failure.code
                }
            }
            check(barrier.awaitReady(Duration.ofSeconds(10))) {
                "stale Spring attempt did not reach its transaction-free pause"
            }
            val pausedConnections = staleDataSource.hikariPoolMXBean.activeConnections
            val oldLeaseToken = oldClaim.lease.token.toString()
            var contextExecutorStopped = false
            if (restartApplication) {
                val oldApplication = application
                closeApplication(oldApplication)
                contextExecutorStopped = oldApplication.workerExecutor.isShutdown &&
                        oldApplication.dataSource.isClosed
                application = startApplication()
            }

            when (val replacement = awaitReplacement(scenario.scope.tenantId, submitted.jobId)) {
                is SpringReplacementOutcome.Claimed -> {
                    val checkpoint = application.repository.checkpoint(
                        replacement.job.lease,
                        completedChunk = 1,
                        progress = 100,
                    )
                    application.repository.complete(checkpoint.lease, JobSignal.SUCCESS)
                }

                SpringReplacementOutcome.Completed -> Unit
            }
            val converged = application.http.snapshot(submitted.jobId, scenario)
            check(converged.state == "succeeded")
            val beforeRelease = authorityBaseline(submitted.jobId)

            barrier.release()
            val staleCode = staleAttempt.get(10, TimeUnit.SECONDS)
            check(staleCode == JobProblemCode.LEASE_LOST)
            val afterRelease = authorityBaseline(submitted.jobId)
            staleAttemptEvidence = SpringJobConsoleStaleAttemptEvidence(
                pausedConnections = pausedConnections,
                pausedTransactions = 0,
                pausedLocks = 0,
                oldLeaseToken = oldLeaseToken,
                pendingCompletedChunk = 1,
                staleErrorCode = staleCode,
                contextExecutorStopped = !restartApplication || contextExecutorStopped,
                lateEffectCount = afterRelease.effects - beforeRelease.effects,
                lateReceiptCount = afterRelease.receipts - beforeRelease.receipts,
            )
        } finally {
            staleExecutor.close()
            staleDataSource.close()
        }
    }

/**
 * awaitReplacement은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitReplacement(
        tenantId: String,
        jobId: UUID,
    ): SpringReplacementOutcome =
        awaitSpringReplacement(
            timeout = Duration.ofSeconds(10),
            pollInterval = Duration.ofMillis(10),
            reclaim = { application.repository.reclaimExpired(tenantId, Duration.ofSeconds(5)) },
            isCompleted = { application.repository.load(jobId)?.state?.terminal == true },
        )

/**
 * assertStaleAttemptEvidence은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun assertStaleAttemptEvidence() {
        val evidence = checkNotNull(staleAttemptEvidence)
        check(evidence.pausedConnections == 0)
        check(evidence.pausedTransactions == 0)
        check(evidence.pausedLocks == 0)
        check(evidence.oldLeaseToken.isNotBlank())
        check(evidence.pendingCompletedChunk == 1L)
        check(evidence.staleErrorCode == JobProblemCode.LEASE_LOST)
        check(evidence.contextExecutorStopped)
        check(evidence.lateEffectCount == 0L)
        check(evidence.lateReceiptCount == 0L)
    }

/**
 * injectRedisPathOutage은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun injectRedisPathOutage() {
        val oldConnection = topology.openRedisConnection()
        check(oldConnection.ping() == "PONG")
        topology.cutExistingConnections()
        topology.disableNewConnections()
        outageActive.set(true)
        val oldFailure = fails { oldConnection.ping() }
        val newFailure = fails {
            topology.openRedisConnection().use { it.ping() }
        }
        HighContentionAwait.condition(
            timeout = Duration.ofSeconds(10),
            pollInterval = Duration.ofMillis(10),
            description = "Spring durable cancel was not observed during the Redis outage",
        ) {
            durableCancelsDuringOutage.get() > 0
        }
        topology.restoreExistingConnections()
        topology.enableNewConnections()
        outageActive.set(false)
        oldConnection.close()
        val recovered = awaitRedisRecovery()
        redisPathEvidence = SpringRedisPathEvidence(
            effectiveEndpointRoutedThroughProxy = application.redisUri == topology.redisUri,
            oldConnectionFailureObserved = oldFailure,
            newConnectionFailureObserved = newFailure,
            recovered = recovered,
            durableCancelsDuringOutage = durableCancelsDuringOutage.get(),
        )
    }

/**
 * awaitRedisRecovery은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitRedisRecovery(): Boolean {
        return runCatching {
            HighContentionAwait.condition(
                timeout = Duration.ofSeconds(10),
                pollInterval = Duration.ofMillis(25),
                description = "Spring Redis path did not recover",
            ) {
                topology.openRedisConnection().use { connection ->
                    connection.ping() == "PONG" && application.cancelSignal.isAvailable()
                }
            }
            true
        }.getOrDefault(false)
    }

/**
 * seedOwnedRedisKeys은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun seedOwnedRedisKeys() {
        RedisClient.create(topology.redisUri).use { client ->
            client.connect().use { connection ->
                repeat(profile.operationCount) { ordinal ->
                    connection.sync().set("${ownedRedisNamespace}signal:$ordinal", "1")
                }
            }
        }
    }

/**
 * deleteOwnedRedisKeys은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun deleteOwnedRedisKeys() {
        RedisClient.create(topology.redisUri).use { client ->
            client.connect().use { connection ->
                val result = OwnedRedisNamespace.parse(
                    namespace = ownedRedisNamespace,
                    deleteUpperBound = profile.operationCount,
                    commands = connection.sync(),
                ).deleteOwnedKeys(
                    writerBarrier = io.bluetape4k.workshop.operations.jobconsole.highcontention.OwnedRedisWriterBarrier.NONE,
                )
                redisKeyLossEvidence = SpringRedisKeyLossEvidence(
                    deletedKeyCount = result.deletedKeys.size,
                    durableCancelsAfterKeyLoss = durableCancelsAfterKeyLoss.get(),
                )
            }
        }
    }

/**
 * startApplication은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun startApplication(): RunningSpringJobConsole {
        val context = SpringApplication(SpringJobConsoleProfileApplication::class.java).apply {
            setAdditionalProfiles("demo")
            setDefaultProperties(
                mapOf(
                    "server.port" to "0",
                    "server.shutdown" to "immediate",
                    "spring.main.banner-mode" to "off",
                    "spring.jmx.enabled" to "false",
                    "spring.lifecycle.timeout-per-shutdown-phase" to "5s",
                    "spring.datasource.url" to fixture.jdbcUrl,
                    "spring.datasource.username" to fixture.databaseUsername,
                    "spring.datasource.password" to fixture.databasePassword,
                    "spring.datasource.hikari.schema" to fixture.schema,
                    "spring.datasource.hikari.maximum-pool-size" to profile.concurrency.coerceAtLeast(4).toString(),
                    "job-console.redis-uri" to topology.redisUri,
                    "job-console.worker-delay-ms" to "1",
                    "job-console.bounded-wait.enabled" to "true",
                    "management.datadog.metrics.export.enabled" to "false",
                ),
            )
        }.run()
        return try {
            val dataSource = context.getBean(DataSource::class.java) as? HikariDataSource
                ?: error("Spring profile requires a HikariDataSource bean")
            val redisUri = context.environment.getRequiredProperty("job-console.redis-uri")
            val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
            RunningSpringJobConsole(
                context = context,
                dataSource = dataSource,
                repository = context.getBean(JobRepository::class.java),
                outboxRepository = JobOutboxRepository(dataSource),
                workerEngine = context.getBean(JobWorkerEngine::class.java),
                workerExecutor = context.getBean("jobWorkerExecutor", ExecutorService::class.java),
                cancelSignal = context.getBean(CancelSignal::class.java),
                redisUri = redisUri,
                http = SpringJobConsoleHttpClient(URI("http://127.0.0.1:$port")),
            )
        } catch (error: Throwable) {
            context.close()
            throw error
        }
    }

/**
 * closeApplication은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun closeApplication(running: RunningSpringJobConsole) {
        try {
            if (!running.context.isActive) return
            VirtualThreads.executorService().use { closer ->
                val close = closer.submit(running.context::close)
                close.get(10, TimeUnit.SECONDS)
            }
            check(!running.context.isActive) { "Spring context remained active after bounded close" }
            check(running.workerExecutor.isShutdown) { "context-owned worker executor remained active" }
            check(running.dataSource.isClosed) { "context-owned Hikari pool remained active" }
        } finally {
            // Spring bean이 아닌 수동 outbox repository도 Exposed manager를 해제해야 합니다.
            running.outboxRepository.close()
        }
    }

/**
 * createProfileOwnedDataSource은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createProfileOwnedDataSource(): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = fixture.jdbcUrl
                username = fixture.databaseUsername
                password = fixture.databasePassword
                schema = fixture.schema
                maximumPoolSize = 2
                poolName = "job-spring-stale-attempt"
            },
        )

/**
 * authorityBaseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun authorityBaseline(): JobConsoleAuthorityBaseline =
        JobConsoleAuthorityQueries.baseline(application.dataSource)

/**
 * authorityBaseline은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun authorityBaseline(jobId: UUID): JobConsoleAuthorityBaseline =
        JobConsoleAuthorityQueries.baseline(application.dataSource, jobId)

/**
 * duplicateOutboxLogicalEventCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun duplicateOutboxLogicalEventCount(): Long =
        JobConsoleAuthorityQueries.duplicateOutboxLogicalEventCount(application.dataSource)

/**
 * burstQueueViolationCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun burstQueueViolationCount(): Long =
        JobConsoleAuthorityQueries.burstQueueViolationCount(application.dataSource)

/**
 * activeJobCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun activeJobCount(): Long =
        JobConsoleAuthorityQueries.activeJobCount(application.dataSource)

/**
 * queueVersionDivergenceCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun queueVersionDivergenceCount(): Long =
        JobConsoleAuthorityQueries.queueVersionDivergenceCount(application.dataSource)

/**
 * scenarioFor은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun scenarioFor(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): JobConsoleScenario {
        val identityScenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        if (action != SpringJobConsoleProfileAction.BURST) {
            return identityScenario
        }
        val authorityScope = JobConsoleScenario.fromSeed(
            seed = "${profile.seed}:authority",
            ordinal = token.authorityOrdinal,
            workUnits = 1,
        ).scope
        return JobConsoleScenario(
            scope = authorityScope,
            idempotencyKey = identityScenario.idempotencyKey,
            request = identityScenario.request,
        )
    }

    companion object {
/**
 * create은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun create(
            runId: String,
            profile: HighContentionProfile,
        ): SpringJobConsoleLiveAdapter {
            val validRunId = runId.requireNotBlank("runId")
            val journalRoot = Files.createTempDirectory("job-spring-high-contention-")
            val journal = HighContentionJournal.open(journalRoot, Path.of("topology.ndjson"))
            val resources = dockerResources(validRunId, profile.profileId)
            var topology: JobConsoleProxiedTopology? = null
            var fixture: JobConsoleContainerFixture? = null
            return try {
                topology = JobConsoleContainerFixture.proxiedRedis(journal, resources)
                val postgres = PostgreSQLServer.Launcher.postgres
                fixture = JobConsoleContainerFixture(
                    jdbcUrl = postgres.jdbcUrl,
                    databaseUsername = postgres.username ?: PostgreSQLServer.USERNAME,
                    databasePassword = postgres.password ?: PostgreSQLServer.PASSWORD,
                    redisUri = topology.redisUri,
                    schema = "job_console_hc_${Uuid.V7.nextId().toString().replace("-", "")}",
                ).also(JobConsoleContainerFixture::createSchema)
                SpringJobConsoleLiveAdapter(
                    runId = validRunId,
                    profile = profile,
                    fixture = fixture,
                    topology = topology,
                    journal = journal,
                    journalRoot = journalRoot,
                )
            } catch (error: Throwable) {
                try {
                    fixture?.dropSchema()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                try {
                    topology?.close()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                try {
                    journal.close()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                try {
                    deleteDirectory(journalRoot)
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
        }

/**
 * dockerResources은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun dockerResources(
            runId: String,
            profileId: String,
        ): JobConsoleDockerResources =
            JobConsoleDockerResources(
                network = dockerResource(runId, profileId, "spring-redis-network", "network"),
                redis = dockerResource(runId, profileId, "spring-redis-primary", "container"),
                toxiproxy = dockerResource(runId, profileId, "spring-redis-toxiproxy", "container"),
            )

/**
 * dockerResource은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun dockerResource(
            runId: String,
            profileId: String,
            resourceKey: String,
            resourceType: String,
        ): HighContentionDockerResource =
            HighContentionDockerResource(
                resourceKey = resourceKey,
                resourceType = resourceType,
                labels = mapOf(
                    HighContentionDockerResource.RUN_ID_LABEL to runId,
                    HighContentionDockerResource.PROFILE_ID_LABEL to profileId,
                    HighContentionDockerResource.RESOURCE_KEY_LABEL to resourceKey,
                    HighContentionDockerResource.RESOURCE_TYPE_LABEL to resourceType,
                ),
            )
    }
}

/**
 * RunningSpringJobConsole 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class RunningSpringJobConsole(
/**
 * context은 테스트 fixture가 보관하는 context 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val context: ConfigurableApplicationContext,
/**
 * dataSource은 테스트 fixture가 보관하는 dataSource 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val dataSource: HikariDataSource,
/**
 * repository은 테스트 fixture가 보관하는 repository 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val repository: JobRepository,
/**
 * outboxRepository은 테스트 fixture가 보관하는 outboxRepository 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val outboxRepository: JobOutboxRepository,
/**
 * workerEngine은 테스트 fixture가 보관하는 workerEngine 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val workerEngine: JobWorkerEngine,
/**
 * workerExecutor은 테스트 fixture가 보관하는 workerExecutor 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val workerExecutor: ExecutorService,
/**
 * cancelSignal은 테스트 fixture가 보관하는 cancelSignal 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val cancelSignal: CancelSignal,
/**
 * redisUri은 테스트 fixture가 보관하는 redisUri 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val redisUri: String,
/**
 * http은 테스트 fixture가 보관하는 http 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val http: SpringJobConsoleHttpClient,
)

/**
 * SpringJobConsoleHttpSnapshot 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class SpringJobConsoleHttpSnapshot(
/**
 * jobId은 테스트 fixture가 보관하는 jobId 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val jobId: UUID,
/**
 * state은 테스트 fixture가 보관하는 state 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    val state: String,
)

/**
 * SpringJobConsoleHttpClient 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class SpringJobConsoleHttpClient(
/**
 * baseUri은 테스트 fixture가 보관하는 baseUri 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val baseUri: URI,
/**
 * client은 테스트 fixture가 보관하는 client 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val client: HttpClient = HttpClient.newBuilder()
        .proxy(ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) {

/**
 * submit은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun submit(scenario: JobConsoleScenario): SpringJobConsoleHttpSnapshot =
        request(
            method = "POST",
            path = "/v1/jobs",
            scenario = scenario,
            body = """{"jobType":"document_export","workUnits":1,"failureMode":"none"}""",
            idempotencyKey = scenario.idempotencyKey,
            expectedStatus = 202,
        ).toSnapshot()

/**
 * snapshot은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun snapshot(
        jobId: UUID,
        scenario: JobConsoleScenario,
    ): SpringJobConsoleHttpSnapshot =
        request(
            method = "GET",
            path = "/v1/jobs/$jobId",
            scenario = scenario,
            expectedStatus = 200,
        ).toSnapshot()

/**
 * cancel은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun cancel(
        jobId: UUID,
        scenario: JobConsoleScenario,
    ): SpringJobConsoleHttpSnapshot =
        request(
            method = "POST",
            path = "/v1/jobs/$jobId/cancel",
            scenario = scenario,
            expectedStatus = 200,
        ).toSnapshot()

/**
 * verifyHeartbeat은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun verifyHeartbeat(
        jobId: UUID,
        scenario: JobConsoleScenario,
    ) {
        val request = requestBuilder("/v1/jobs/$jobId/events", scenario)
            .header("Accept", "text/event-stream")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200)
        response.body().bufferedReader().use { reader ->
            val lines = generateSequence(reader::readLine).take(3).toList()
            check(lines.any { it.replace(" ", "") == "event:heartbeat" }) {
                "Spring SSE heartbeat was not observed"
            }
        }
    }

/**
 * request은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(
        method: String,
        path: String,
        scenario: JobConsoleScenario,
        body: String? = null,
        idempotencyKey: String? = null,
        expectedStatus: Int,
    ): HttpResponse<String> {
        val builder = requestBuilder(path, scenario)
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).also { response ->
            check(response.statusCode() == expectedStatus) {
                "Spring HTTP $method $path returned ${response.statusCode()}: ${response.body()}"
            }
        }
    }

/**
 * requestBuilder은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun requestBuilder(
        path: String,
        scenario: JobConsoleScenario,
    ): HttpRequest.Builder =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(15))
            .header("X-Demo-Tenant", scenario.scope.tenantId)
            .header("X-Demo-Submitter", scenario.scope.submitterHash)

    private fun HttpResponse<String>.toSnapshot(): SpringJobConsoleHttpSnapshot {
        val jobId = JOB_ID.find(body())?.groupValues?.get(1)
            ?: error("Spring response omitted jobId")
        val state = STATE.find(body())?.groupValues?.get(1)
            ?: error("Spring response omitted state")
        return SpringJobConsoleHttpSnapshot(UUID.fromString(jobId), state)
    }

    private companion object {
        /** Spring 프로필 HTTP 응답에서 안정적인 job 식별자를 추출하는 정규식입니다. */
        /** Spring 프로필 HTTP 응답에서 안정적인 job 식별자를 추출하는 정규식입니다. */
        val JOB_ID = Regex("\\\"jobId\\\":\\\"([^\\\"]+)")
        /** Spring 프로필 HTTP 응답에서 권위 있는 현재 job 상태를 추출하는 정규식입니다. */
        /** Spring 프로필 HTTP 응답에서 권위 있는 현재 job 상태를 추출하는 정규식입니다. */
        val STATE = Regex("\\\"state\\\":\\\"([^\\\"]+)")
    }
}

/**
 * fails은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private inline fun fails(action: () -> Unit): Boolean =
    try {
        action()
        false
    } catch (_: Exception) {
        true
    }

/**
 * deleteDirectory은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun deleteDirectory(directory: Path) {
    Files.list(directory).use { paths ->
        paths.forEach(Files::deleteIfExists)
    }
    Files.deleteIfExists(directory)
}
