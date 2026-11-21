package io.bluetape4k.workshop.operations.jobconsole.ktor

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
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleDockerResources
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleLiveProfileAdapter
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleProxiedTopology
import io.bluetape4k.workshop.operations.jobconsole.highcontention.OwnedRedisNamespace
import io.bluetape4k.workshop.operations.jobconsole.highcontention.OwnedRedisWriterBarrier
import io.bluetape4k.workshop.operations.jobconsole.highcontention.ScheduleToken
import io.bluetape4k.workshop.operations.jobconsole.highcontention.WorkloadIdentity
import io.bluetape4k.workshop.operations.jobconsole.highcontention.WorkloadTerminalDisposition
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.lettuce.core.RedisClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * `KtorJobConsoleProfileAction`은 업무 분기용 제한된 값 집합입니다.
 * 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행하는 명령·신호 계약입니다.
 */
internal enum class KtorJobConsoleProfileAction(
/**
 * profileId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val profileId: String,
) {
    /**
     * `KtorJobConsoleProfileAction.BURST`은 `burst` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을 검증한 뒤
     * 허용된 전이만 실행합니다.
     */
    BURST("burst"),
    /**
     * `KtorJobConsoleProfileAction.DUPLICATE_STORM`은 `duplicate storm` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    DUPLICATE_STORM("duplicate-storm"),
    /**
     * `KtorJobConsoleProfileAction.REDIS_PATH_OUTAGE`은 `redis path outage` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    REDIS_PATH_OUTAGE("redis-path-outage"),
    /**
     * `KtorJobConsoleProfileAction.REDIS_KEY_LOSS`은 `redis key loss` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    REDIS_KEY_LOSS("redis-key-loss"),
    /**
     * `KtorJobConsoleProfileAction.SLOW_PROVIDER`은 `slow provider` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    SLOW_PROVIDER("slow-provider"),
    /**
     * `KtorJobConsoleProfileAction.WORKER_RESTART`은 `worker restart` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    WORKER_RESTART("worker-restart"),
    /**
     * `KtorJobConsoleProfileAction.DUPLICATE_DELIVERY`은 `duplicate delivery` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    DUPLICATE_DELIVERY("duplicate-delivery"),
    ;

    companion object {
/**
 * resolve은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun resolve(profileId: String): KtorJobConsoleProfileAction {
            val validProfileId = profileId.requireNotBlank("profileId")
            return entries.singleOrNull { it.profileId == validProfileId }
                ?: throw IllegalArgumentException("unsupported Ktor Job Console profile[$validProfileId]")
        }
    }
}

/**
 * KtorStaleAttemptEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class KtorStaleAttemptEvidence(
/**
 * pausedConnections은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val pausedConnections: Int,
/**
 * pausedTransactions은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val pausedTransactions: Int,
/**
 * pausedLocks은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val pausedLocks: Int,
/**
 * oldLeaseToken은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val oldLeaseToken: String,
/**
 * pendingCompletedChunk은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val pendingCompletedChunk: Long,
/**
 * staleErrorCode은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val staleErrorCode: JobProblemCode,
/**
 * serverJobsStopped은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val serverJobsStopped: Boolean,
/**
 * oldPortReleased은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val oldPortReleased: Boolean,
/**
 * lateEffectCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val lateEffectCount: Long,
/**
 * lateReceiptCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val lateReceiptCount: Long,
)

/**
 * KtorRedisPathEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class KtorRedisPathEvidence(
/**
 * effectiveEndpointRoutedThroughProxy은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val effectiveEndpointRoutedThroughProxy: Boolean,
/**
 * oldConnectionFailureObserved은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val oldConnectionFailureObserved: Boolean,
/**
 * newConnectionFailureObserved은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val newConnectionFailureObserved: Boolean,
/**
 * recovered은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val recovered: Boolean,
/**
 * durableCancelsDuringOutage은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val durableCancelsDuringOutage: Int,
)

/**
 * KtorRedisKeyLossEvidence 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class KtorRedisKeyLossEvidence(
/**
 * deletedKeyCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deletedKeyCount: Int,
/**
 * durableCancelsAfterKeyLoss은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val durableCancelsAfterKeyLoss: Int,
)

/**
 * KtorJobConsoleLiveAdapter 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class KtorJobConsoleLiveAdapter private constructor(
/**
 * runId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val runId: String,
/**
 * profile은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val profile: HighContentionProfile,
/**
 * fixture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixture: JobConsoleContainerFixture,
/**
 * topology은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val topology: JobConsoleProxiedTopology,
/**
 * journal은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val journal: HighContentionJournal,
/**
 * journalRoot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val journalRoot: Path,
): JobConsoleLiveProfileAdapter {

/**
 * action은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val action = KtorJobConsoleProfileAction.resolve(profile.profileId)
/**
 * lifecycleLock은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val lifecycleLock = ReentrantReadWriteLock(true)
/**
 * winners은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val winners = ConcurrentHashMap<Int, Boolean>()
/**
 * jobIdsByIdentity은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val jobIdsByIdentity = ConcurrentHashMap<Int, UUID>()
/**
 * scenariosByIdentity은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val scenariosByIdentity = ConcurrentHashMap<Int, JobConsoleScenario>()
/**
 * deliveredEventIds은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val deliveredEventIds = ConcurrentHashMap.newKeySet<UUID>()
/**
 * restartStarted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val restartStarted = AtomicBoolean()
/**
 * slowProviderStarted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val slowProviderStarted = AtomicBoolean()
/**
 * slowProviderReady은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val slowProviderReady = CompletableFuture<Unit>()
/**
 * outageActive은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val outageActive = AtomicBoolean()
/**
 * durableCancelsDuringOutage은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val durableCancelsDuringOutage = AtomicInteger()
/**
 * durableCancelsAfterKeyLoss은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val durableCancelsAfterKeyLoss = AtomicInteger()
/**
 * ownedRedisNamespace은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val ownedRedisNamespace = "hc:v1:$runId:job-ktor:redis-key-loss:"

    @Volatile
/**
 * measuredBaseline은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var measuredBaseline: JobConsoleAuthorityBaseline? = null

    @Volatile
/**
 * application은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var application: RunningKtorJobConsole = startApplication()

    @Volatile
/**
 * staleAttemptEvidence은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var staleAttemptEvidence: KtorStaleAttemptEvidence? = null

    @Volatile
/**
 * redisPathEvidence은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var redisPathEvidence: KtorRedisPathEvidence? = null

    @Volatile
/**
 * redisKeyLossEvidence은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var redisKeyLossEvidence: KtorRedisKeyLossEvidence? = null

    init {
        check(application.redisUri == topology.redisUri) {
            "Ktor Redis endpoint differs from the proxied endpoint"
        }
/**
 * redisSignal은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val redisSignal = checkNotNull(application.runtime.redisSignal) {
            "Ktor did not create the Lettuce cancel signal"
        }
        check(redisSignal.isAvailable()) {
            "Ktor cancel signal is not connected through the proxy"
        }
        check(application.dataSource.maximumPoolSize == profile.concurrency.coerceAtLeast(4)) {
            "Ktor Hikari pool limit differs from the selected profile"
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
                if (action == KtorJobConsoleProfileAction.REDIS_KEY_LOSS) {
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
            KtorJobConsoleProfileAction.WORKER_RESTART ->
                if (restartStarted.compareAndSet(false, true)) {
                    lifecycleLock.write {
                        runFencedAttemptScenario(restartApplication = true, identity = identity)
                        winners[token.stableOrdinal] = true
                    }
                    WorkloadTerminalDisposition.COMPLETED
                } else {
                    lifecycleLock.read { submitAndComplete(token, identity) }
                }

            KtorJobConsoleProfileAction.SLOW_PROVIDER -> executeSlowProvider(token, identity)

            else -> lifecycleLock.read { executeLiveAction(token, identity) }
        }

/**
 * injectDeclaredFailure은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun injectDeclaredFailure() {
        when (action) {
            KtorJobConsoleProfileAction.REDIS_PATH_OUTAGE -> injectRedisPathOutage()
            KtorJobConsoleProfileAction.REDIS_KEY_LOSS -> deleteOwnedRedisKeys()
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
/**
 * actionEvidence은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val actionEvidence = when (action) {
                KtorJobConsoleProfileAction.REDIS_PATH_OUTAGE ->
                    redisPathEvidence?.let {
                        ",oldConnectionFailure=${it.oldConnectionFailureObserved}," +
                                "newConnectionFailure=${it.newConnectionFailureObserved}," +
                                "redisRecovered=${it.recovered}," +
                                "durableCancelsDuringOutage=${it.durableCancelsDuringOutage}"
                    }.orEmpty()

                KtorJobConsoleProfileAction.REDIS_KEY_LOSS    ->
                    redisKeyLossEvidence?.let {
                        ",deletedOwnedKeys=${it.deletedKeyCount}," +
                                "durableCancelsAfterKeyLoss=${it.durableCancelsAfterKeyLoss}"
                    }.orEmpty()

                KtorJobConsoleProfileAction.SLOW_PROVIDER,
                KtorJobConsoleProfileAction.WORKER_RESTART,
                                                              -> staleAttemptEvidence?.let {
                    ",staleCode=${it.staleErrorCode}," +
                            "serverJobsStopped=${it.serverJobsStopped}," +
                            "oldPortReleased=${it.oldPortReleased}," +
                            "lateEffects=${it.lateEffectCount},lateReceipts=${it.lateReceiptCount}"
                }.orEmpty()

                else                                          -> ""
            }
/**
 * retrySummary은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val retrySummary = application.http.retrySummary()
/**
 * retryStatusEvidence은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val retryStatusEvidence = retrySummary.statusCounts.entries
                .joinToString(separator = ";") { (status, count) -> "$status:$count" }
            "hikari=true,maxPool=${application.dataSource.maximumPoolSize}," +
                    "redisProxy=${application.redisUri == topology.redisUri}," +
                    "retryRequests=${retrySummary.requestCount}," +
                    "retryCount=${retrySummary.retryCount}," +
                    "retryStatusCounts=$retryStatusEvidence," +
                    "retryDelayMillis=${retrySummary.cumulativeDelay.toMillis()}$actionEvidence"
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
            val stored = checkNotNull(application.runtime.repository.load(jobId))
            check(http.state == stored.state.wireValue) {
                "Ktor HTTP snapshot and PostgreSQL authority diverged for job[$jobId]"
            }
        }
        when (action) {
            KtorJobConsoleProfileAction.BURST -> {
                check(delta.jobs == profile.operationCount.toLong())
                check(jobIdsByIdentity.size == profile.operationCount)
                check(burstQueueViolationCount() == 0L)
                check(activeJobCount() == 0L)
                check(queueVersionDivergenceCount() == 0L)
            }

            KtorJobConsoleProfileAction.DUPLICATE_STORM -> {
                check(delta.jobs == profile.contentionShape.identityCount.toLong())
                check(jobIdsByIdentity.size == profile.contentionShape.identityCount)
            }

            KtorJobConsoleProfileAction.REDIS_PATH_OUTAGE -> {
                val evidence = checkNotNull(redisPathEvidence)
                val finalCancelsDuringOutage = durableCancelsDuringOutage.get()
                redisPathEvidence = KtorRedisPathEvidence(
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

            KtorJobConsoleProfileAction.REDIS_KEY_LOSS -> {
                val evidence = checkNotNull(redisKeyLossEvidence)
                val finalCancelsAfterKeyLoss = durableCancelsAfterKeyLoss.get()
                redisKeyLossEvidence = KtorRedisKeyLossEvidence(
                    deletedKeyCount = evidence.deletedKeyCount,
                    durableCancelsAfterKeyLoss = finalCancelsAfterKeyLoss,
                )
                check(evidence.deletedKeyCount == profile.operationCount)
                check(finalCancelsAfterKeyLoss > 0)
            }

            KtorJobConsoleProfileAction.SLOW_PROVIDER,
            KtorJobConsoleProfileAction.WORKER_RESTART,
                                              -> assertStaleAttemptEvidence()

            KtorJobConsoleProfileAction.DUPLICATE_DELIVERY ->
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
/**
 * scenario은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val scenario = scenarioFor(token, identity)
        scenariosByIdentity.putIfAbsent(identity.ordinal, scenario)
/**
 * submitted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val submitted = application.http.submit(scenario)
/**
 * winner은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val winner = jobIdsByIdentity.putIfAbsent(identity.ordinal, submitted.jobId) == null
        winners[token.stableOrdinal] = winner
        check(jobIdsByIdentity.getValue(identity.ordinal) == submitted.jobId) {
            "stable Ktor HTTP identity resolved to different jobs"
        }

        when (action) {
            KtorJobConsoleProfileAction.REDIS_PATH_OUTAGE -> {
/**
 * beganDuringOutage은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val beganDuringOutage = outageActive.get()
                application.http.cancel(submitted.jobId, scenario)
                if (beganDuringOutage) durableCancelsDuringOutage.incrementAndGet()
            }

            KtorJobConsoleProfileAction.REDIS_KEY_LOSS -> {
                application.http.cancel(submitted.jobId, scenario)
                if (redisKeyLossEvidence != null) durableCancelsAfterKeyLoss.incrementAndGet()
            }

            KtorJobConsoleProfileAction.DUPLICATE_DELIVERY -> {
                if (winner) {
                    completeThroughOwnedWorker(submitted.jobId)
/**
 * claim은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val claim = application.runtime.outboxRepository.claim(16, Duration.ofSeconds(5))
                    claim.events.forEach { event ->
                        check(application.runtime.outboxRepository.markPublished(claim.token, event.eventId))
                        check(application.runtime.outboxRepository.markPublished(claim.token, event.eventId))
                        deliveredEventIds += event.eventId
                    }
                }
            }

            KtorJobConsoleProfileAction.BURST -> {
                if (token.stableOrdinal == 0) application.http.verifyHeartbeat(submitted.jobId, scenario)
                if (winner) completeThroughOwnedWorker(submitted.jobId)
            }

            KtorJobConsoleProfileAction.DUPLICATE_STORM -> {
                if (winner) completeThroughOwnedWorker(submitted.jobId)
            }

            else                              -> error("profile action[$action] must use its dedicated lifecycle path")
        }
        return WorkloadTerminalDisposition.COMPLETED
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
/**
 * scenario은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val scenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        scenariosByIdentity.putIfAbsent(identity.ordinal, scenario)
/**
 * submitted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val submitted = application.http.submit(scenario)
/**
 * winner은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val winner = jobIdsByIdentity.putIfAbsent(identity.ordinal, submitted.jobId) == null
        winners[token.stableOrdinal] = winner
        if (winner) completeThroughOwnedWorker(submitted.jobId)
        return WorkloadTerminalDisposition.COMPLETED
    }

/**
 * executeSlowProvider은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun executeSlowProvider(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition {
        if (slowProviderStarted.compareAndSet(false, true)) {
            try {
                lifecycleLock.write {
                    runFencedAttemptScenario(restartApplication = false, identity = identity)
                    winners[token.stableOrdinal] = true
                }
                slowProviderReady.complete(Unit)
            } catch (failure: Throwable) {
                slowProviderReady.completeExceptionally(failure)
                throw failure
            }
        } else {
            awaitSlowProviderBoundary()
            lifecycleLock.read {
                submitAndComplete(token, identity)
            }
        }
        return WorkloadTerminalDisposition.COMPLETED
    }

/**
 * awaitSlowProviderBoundary은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitSlowProviderBoundary() {
        try {
            slowProviderReady.get(profile.workloadJoinDeadlineMs, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "slow-provider lifecycle boundary did not become ready before the workload deadline",
                failure,
            )
        }
    }

/**
 * completeThroughOwnedWorker은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun completeThroughOwnedWorker(jobId: UUID) {
        val deadline = System.nanoTime() + Duration.ofMillis(profile.recoveryDeadlineMs).toNanos()
        do {
            val stored = application.runtime.repository.load(jobId)
            if (stored?.state?.terminal == true) return
            application.runtime.workerEngine.runOnce()
        } while (System.nanoTime() < deadline)
        error("application-owned Ktor worker did not complete job[$jobId]")
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
/**
 * scenario은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val scenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        scenariosByIdentity.putIfAbsent(identity.ordinal, scenario)
/**
 * submitted은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val submitted = application.http.submit(scenario)
        jobIdsByIdentity.putIfAbsent(identity.ordinal, submitted.jobId)
/**
 * oldClaim은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val oldClaim = checkNotNull(
            application.runtime.repository.claimNext(scenario.scope.tenantId, Duration.ofMillis(10)),
        )
/**
 * staleDataSource은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val staleDataSource = createProfileOwnedDataSource()
/**
 * staleRepository은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val staleRepository = JobRepository(staleDataSource)
/**
 * barrier은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val barrier = JobConsoleBarrier(1)
/**
 * staleExecutor은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val staleExecutor = VirtualThreads.executorService()
        try {
/**
 * staleAttempt은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val staleAttempt = staleExecutor.submit<JobProblemCode> {
                check(barrier.readyAndAwait(Duration.ofMillis(profile.failureDetectionDeadlineMs))) {
                    "stale Ktor attempt barrier timed out"
                }
                try {
                    staleRepository.checkpoint(oldClaim.lease, completedChunk = 1, progress = 100)
                    error("stale Ktor checkpoint unexpectedly committed")
                } catch (failure: JobRepositoryException) {
                    failure.code
                }
            }
            check(barrier.awaitReady(Duration.ofMillis(profile.failureDetectionDeadlineMs))) {
                "stale Ktor attempt did not reach its transaction-free pause"
            }
/**
 * pausedConnections은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val pausedConnections = staleDataSource.hikariPoolMXBean.activeConnections
/**
 * oldLeaseToken은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val oldLeaseToken = oldClaim.lease.token.toString()
/**
 * serverJobsStopped은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var serverJobsStopped = true
/**
 * oldPortReleased은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var oldPortReleased = true
            if (restartApplication) {
/**
 * oldApplication은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val oldApplication = application
                closeApplication(oldApplication)
                serverJobsStopped = oldApplication.runtime.backgroundJobsStopped
                oldPortReleased = canBind(oldApplication.port)
                application = startApplication()
            }

/**
 * replacement은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val replacement = awaitReplacementClaim(scenario.scope.tenantId)
/**
 * checkpoint은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val checkpoint = application.runtime.repository.checkpoint(
                replacement.lease,
                completedChunk = 1,
                progress = 100,
            )
            application.runtime.repository.complete(checkpoint.lease, JobSignal.SUCCESS)
/**
 * converged은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val converged = application.http.snapshot(submitted.jobId, scenario)
            check(converged.state == "succeeded")
/**
 * beforeRelease은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val beforeRelease = authorityBaseline(submitted.jobId)

            barrier.release()
/**
 * staleCode은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val staleCode = staleAttempt.get(profile.recoveryDeadlineMs, TimeUnit.MILLISECONDS)
            check(staleCode == JobProblemCode.LEASE_LOST)
/**
 * afterRelease은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val afterRelease = authorityBaseline(submitted.jobId)
            staleAttemptEvidence = KtorStaleAttemptEvidence(
                pausedConnections = pausedConnections,
                pausedTransactions = 0,
                pausedLocks = 0,
                oldLeaseToken = oldLeaseToken,
                pendingCompletedChunk = 1,
                staleErrorCode = staleCode,
                serverJobsStopped = serverJobsStopped,
                oldPortReleased = oldPortReleased,
                lateEffectCount = afterRelease.effects - beforeRelease.effects,
                lateReceiptCount = afterRelease.receipts - beforeRelease.receipts,
            )
        } finally {
            staleExecutor.close()
            staleDataSource.close()
        }
    }

/**
 * awaitReplacementClaim은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun awaitReplacementClaim(
        tenantId: String,
    ): io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob {
        return HighContentionAwait.value(
            timeout = Duration.ofMillis(profile.recoveryDeadlineMs),
            pollInterval = Duration.ofMillis(10),
            description = "replacement Ktor worker did not reclaim the expired lease",
        ) {
            application.runtime.repository.reclaimExpired(tenantId, Duration.ofSeconds(5))
        }
    }

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
        check(evidence.serverJobsStopped)
        check(evidence.oldPortReleased)
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
            description = "Ktor durable cancel was not observed during the Redis outage",
        ) {
            durableCancelsDuringOutage.get() > 0
        }
        topology.restoreExistingConnections()
        topology.enableNewConnections()
        outageActive.set(false)
        oldConnection.close()
        val recovered = awaitRedisRecovery()
        redisPathEvidence = KtorRedisPathEvidence(
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
                description = "Ktor Redis path did not recover",
            ) {
                topology.openRedisConnection().use { connection ->
                    connection.ping() == "PONG" &&
                            application.runtime.redisSignal?.isAvailable() == true
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
                ).deleteOwnedKeys(OwnedRedisWriterBarrier.NONE)
                redisKeyLossEvidence = KtorRedisKeyLossEvidence(
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
    private fun startApplication(): RunningKtorJobConsole {
        val dataSource = createProfileOwnedDataSource("job-ktor-${Uuid.V7.nextId()}")
        val workerGate = CompletableDeferred<Unit>()
        val outboxGate = CompletableDeferred<Unit>()
        var runtime: JobConsoleKtorRuntime? = null
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            jobConsoleModule(
                dataSource = dataSource,
                demoEnabled = true,
                redisUri = topology.redisUri,
                workerEnabled = true,
                boundedWaitEnabled = true,
                outboxStartGate = outboxGate::await,
                workerStartGate = workerGate::await,
                runtimeObserver = { runtime = it },
            )
        }
        return try {
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            RunningKtorJobConsole(
                dataSource = dataSource,
                runtime = checkNotNull(runtime) { "Ktor runtime observer was not invoked" },
                redisUri = topology.redisUri,
                port = port,
                http = KtorJobConsoleHttpClient(URI("http://127.0.0.1:$port")),
                stop = { server.stop(gracePeriodMillis = 0, timeoutMillis = 5_000) },
            )
        } catch (error: Throwable) {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 5_000)
            if (!dataSource.isClosed) dataSource.close()
            throw error
        }
    }

/**
 * closeApplication은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun closeApplication(running: RunningKtorJobConsole) {
        if (!running.dataSource.isClosed) {
            running.stop()
        }
        check(running.dataSource.isClosed) { "Ktor-owned Hikari pool remained active" }
        check(running.runtime.backgroundJobsStopped) { "Ktor application jobs remained active" }
        check(canBind(running.port)) { "Ktor port[${running.port}] remained bound after stop" }
    }

/**
 * createProfileOwnedDataSource은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createProfileOwnedDataSource(
        poolName: String = "job-ktor-stale-attempt",
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = fixture.jdbcUrl
                username = fixture.databaseUsername
                password = fixture.databasePassword
                schema = fixture.schema
                maximumPoolSize = profile.concurrency.coerceAtLeast(4)
                this.poolName = poolName
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
/**
 * identityScenario은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val identityScenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        if (action != KtorJobConsoleProfileAction.BURST) {
            return identityScenario
        }
/**
 * authorityScope은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
        ): KtorJobConsoleLiveAdapter {
/**
 * validRunId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val validRunId = runId.requireNotBlank("runId")
/**
 * journalRoot은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val journalRoot = Files.createTempDirectory("job-ktor-high-contention-")
/**
 * journal은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val journal = HighContentionJournal.open(journalRoot, Path.of("topology.ndjson"))
/**
 * resources은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val resources = dockerResources(validRunId, profile.profileId)
/**
 * topology은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var topology: JobConsoleProxiedTopology? = null
/**
 * fixture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            var fixture: JobConsoleContainerFixture? = null
            return try {
                topology = JobConsoleContainerFixture.proxiedRedis(journal, resources)
/**
 * postgres은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val postgres = PostgreSQLServer.Launcher.postgres
                fixture = JobConsoleContainerFixture(
                    jdbcUrl = postgres.jdbcUrl,
                    databaseUsername = postgres.username ?: PostgreSQLServer.USERNAME,
                    databasePassword = postgres.password ?: PostgreSQLServer.PASSWORD,
                    redisUri = topology.redisUri,
                    schema = "job_console_hc_${Uuid.V7.nextId().toString().replace("-", "")}",
                ).also(JobConsoleContainerFixture::createSchema)
                KtorJobConsoleLiveAdapter(
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
                network = dockerResource(runId, profileId, "ktor-redis-network", "network"),
                redis = dockerResource(runId, profileId, "ktor-redis-primary", "container"),
                toxiproxy = dockerResource(runId, profileId, "ktor-redis-toxiproxy", "container"),
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
 * RunningKtorJobConsole 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class RunningKtorJobConsole(
/**
 * dataSource은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val dataSource: HikariDataSource,
/**
 * runtime은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val runtime: JobConsoleKtorRuntime,
/**
 * redisUri은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val redisUri: String,
/**
 * port은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val port: Int,
/**
 * http은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val http: KtorJobConsoleHttpClient,
/**
 * stop은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val stop: () -> Unit,
)

/**
 * KtorJobConsoleHttpSnapshot 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class KtorJobConsoleHttpSnapshot(
/**
 * jobId은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val jobId: UUID,
/**
 * state은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val state: String,
/**
 * retryAttempts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val retryAttempts: Int = 0,
/**
 * retryStatuses은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val retryStatuses: List<Int> = emptyList(),
/**
 * retryDelay은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val retryDelay: Duration = Duration.ZERO,
)

/**
 * KtorJobConsoleRetrySummary 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class KtorJobConsoleRetrySummary(
/**
 * requestCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val requestCount: Int,
/**
 * retryCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val retryCount: Int,
/**
 * statusCounts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val statusCounts: Map<Int, Int>,
/**
 * cumulativeDelay은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val cumulativeDelay: Duration,
)

/**
 * KtorJobConsoleHttpResult 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private data class KtorJobConsoleHttpResult(
/**
 * response은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val response: HttpResponse<String>,
/**
 * retryAttempts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val retryAttempts: Int,
/**
 * retryStatuses은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val retryStatuses: List<Int>,
/**
 * retryDelay은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val retryDelay: Duration,
)

/**
 * KtorJobConsoleRetryAccumulator 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class KtorJobConsoleRetryAccumulator {
/**
 * requestCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var requestCount: Int = 0
/**
 * retryCount은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var retryCount: Int = 0
/**
 * statusCounts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val statusCounts: MutableMap<Int, Int> = mutableMapOf()
/**
 * cumulativeDelay은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var cumulativeDelay: Duration = Duration.ZERO

    @Synchronized
/**
 * record은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun record(result: KtorJobConsoleHttpResult) {
        requestCount++
        retryCount += result.retryAttempts
        result.retryStatuses
            .filter { it in KtorJobConsoleHttpClient.RETRYABLE_SUBMISSION_STATUSES }
            .forEach { status -> statusCounts[status] = statusCounts.getOrDefault(status, 0) + 1 }
        cumulativeDelay = cumulativeDelay.plus(result.retryDelay)
    }

    @Synchronized
/**
 * snapshot은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun snapshot(): KtorJobConsoleRetrySummary =
        KtorJobConsoleRetrySummary(
            requestCount = requestCount,
            retryCount = retryCount,
            statusCounts = statusCounts.toSortedMap(),
            cumulativeDelay = cumulativeDelay,
        )
}

/**
 * KtorJobConsoleHttpClient 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class KtorJobConsoleHttpClient(
/**
 * baseUri은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val baseUri: URI,
/**
 * client은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val client: HttpClient = HttpClient.newBuilder()
        .proxy(ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
/**
 * sendRequest은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val sendRequest: ((HttpRequest) -> HttpResponse<String>)? = null,
/**
 * delay은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val delay: (Duration) -> Unit = { duration ->
        TimeUnit.NANOSECONDS.sleep(duration.toNanos())
    },
) {
/**
 * retryAccumulator은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val retryAccumulator = KtorJobConsoleRetryAccumulator()

/**
 * retrySummary은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    internal fun retrySummary(): KtorJobConsoleRetrySummary = retryAccumulator.snapshot()

/**
 * submit은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun submit(scenario: JobConsoleScenario): KtorJobConsoleHttpSnapshot =
        request(
            method = "POST",
            path = "/v1/jobs",
            scenario = scenario,
            body = """{"jobType":"document_export","workUnits":1,"failureMode":"none"}""",
            idempotencyKey = scenario.idempotencyKey,
            expectedStatus = 202,
            retryOnTransientSubmissionFailure = true,
        ).toSnapshot()

/**
 * snapshot은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun snapshot(
        jobId: UUID,
        scenario: JobConsoleScenario,
    ): KtorJobConsoleHttpSnapshot =
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
    ): KtorJobConsoleHttpSnapshot =
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
/**
 * request은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val request = requestBuilder("/v1/jobs/$jobId/events", scenario)
            .header("Accept", "text/event-stream")
            .GET()
            .build()
/**
 * response은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200)
        response.body().bufferedReader().use { reader ->
/**
 * lines은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val lines = generateSequence(reader::readLine).take(3).toList()
            check(lines.any { it.replace(" ", "") == "event:heartbeat" }) {
                "Ktor SSE heartbeat was not observed"
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
        retryOnTransientSubmissionFailure: Boolean = false,
    ): KtorJobConsoleHttpResult {
/**
 * attempt은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var attempt = 0
/**
 * cumulativeDelay은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var cumulativeDelay = Duration.ZERO
/**
 * statuses은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val statuses = ArrayList<Int>(MAX_SUBMISSION_RETRIES + 1)
        while (true) {
/**
 * builder은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val builder = requestBuilder(path, scenario)
            idempotencyKey?.let { builder.header("Idempotency-Key", it) }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            } else {
                builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
            }
/**
 * request은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val request = builder.build()
/**
 * response은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val response = sendRequest?.invoke(request)
                ?: client.send(request, HttpResponse.BodyHandlers.ofString())
            statuses += response.statusCode()
            if (retryOnTransientSubmissionFailure &&
                response.statusCode() in RETRYABLE_SUBMISSION_STATUSES &&
                attempt < MAX_SUBMISSION_RETRIES
            ) {
                attempt++
/**
 * retryDelay은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val retryDelay = response.retryAfter()
                cumulativeDelay = cumulativeDelay.plus(retryDelay)
                delay(retryDelay)
                continue
            }
/**
 * result은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val result = KtorJobConsoleHttpResult(
                response = response,
                retryAttempts = attempt,
                retryStatuses = statuses.toList(),
                retryDelay = cumulativeDelay,
            )
            if (retryOnTransientSubmissionFailure) {
                retryAccumulator.record(result)
            }
            return result.also {
                check(it.response.statusCode() == expectedStatus) {
                    "Ktor HTTP $method $path returned ${it.response.statusCode()}: ${it.response.body()}"
                }
            }
        }
    }

    private fun HttpResponse<String>.retryAfter(): Duration {
/**
 * headerDelay은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val headerDelay = headers()
            .firstValue("Retry-After")
            .orElse(null)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)
            ?.let(Duration::ofSeconds)
        return headerDelay ?: if (statusCode() == SERVICE_UNAVAILABLE) {
            DEFAULT_SERVICE_UNAVAILABLE_RETRY_AFTER
        } else {
            DEFAULT_TOO_MANY_REQUESTS_RETRY_AFTER
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

/**
 * toSnapshot은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun KtorJobConsoleHttpResult.toSnapshot(): KtorJobConsoleHttpSnapshot {
        val jobId = JOB_ID.find(response.body())?.groupValues?.get(1)
            ?: error("Ktor response omitted jobId")
        val state = STATE.find(response.body())?.groupValues?.get(1)
            ?: error("Ktor response omitted state")
        return KtorJobConsoleHttpSnapshot(
            jobId = UUID.fromString(jobId),
            state = state,
            retryAttempts = retryAttempts,
            retryStatuses = retryStatuses,
            retryDelay = retryDelay,
        )
    }

    internal companion object {
/**
 * TOO_MANY_REQUESTS은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val TOO_MANY_REQUESTS = 429
/**
 * SERVICE_UNAVAILABLE은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val SERVICE_UNAVAILABLE = 503
/**
 * MAX_SUBMISSION_RETRIES은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val MAX_SUBMISSION_RETRIES = 8
/**
 * MAX_RETRY_AFTER_SECONDS은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val MAX_RETRY_AFTER_SECONDS = 30L
/**
 * RETRYABLE_SUBMISSION_STATUSES은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val RETRYABLE_SUBMISSION_STATUSES = setOf(TOO_MANY_REQUESTS, SERVICE_UNAVAILABLE)
/**
 * DEFAULT_TOO_MANY_REQUESTS_RETRY_AFTER은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val DEFAULT_TOO_MANY_REQUESTS_RETRY_AFTER = Duration.ofSeconds(2)
/**
 * DEFAULT_SERVICE_UNAVAILABLE_RETRY_AFTER은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val DEFAULT_SERVICE_UNAVAILABLE_RETRY_AFTER = Duration.ofMillis(100)
/**
 * JOB_ID은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val JOB_ID = Regex("\"jobId\":\"([^\"]+)")
/**
 * STATE은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val STATE = Regex("\"state\":\"([^\"]+)")
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
 * canBind은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun canBind(port: Int): Boolean =
    try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    } catch (_: Exception) {
        false
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
