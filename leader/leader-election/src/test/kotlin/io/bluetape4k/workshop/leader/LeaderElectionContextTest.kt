package io.bluetape4k.workshop.leader

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.leader.job.LeaderGuardedJob
import io.bluetape4k.workshop.leader.job.LeaderScheduledJobService
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * T0: Spring Boot context loading test입니다.
 *
 * 전체 application context가 모든 leader election bean과 함께 올바르게 시작되는지 검증합니다.
 * `@Component` 누락, `Duration` type mismatch, `@ConfigurationProperties` binding 실패를
 * production에 도달하기 전에 runtime에서 잡습니다.
 *
 * Testcontainers Redis URL을 주입하려고 `@DynamicPropertySource`를 사용합니다.
 * 이 use case에서는 `@TestPropertySource(properties = ["...${redis.port}"])` pattern이 깨집니다.
 */
@SpringBootTest
class LeaderElectionContextTest(
    @Autowired val leaderElector: LeaderElector,
    @Autowired val jobService: LeaderScheduledJobService,
    @Autowired val jobs: List<LeaderGuardedJob>,
) {
    companion object : KLogging() {
        val redis = RedisServer.Launcher.redis

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("leader.redis.url") { redis.url }
        }
    }

    @Test
    fun `Spring Boot context loads with all leader beans`() {
        leaderElector.shouldNotBeNull()
        jobService.shouldNotBeNull()
        jobs.size shouldBeGreaterOrEqualTo 2  // at least CacheWarmupJob + StaleWorkflowCleanupJob
    }
}
