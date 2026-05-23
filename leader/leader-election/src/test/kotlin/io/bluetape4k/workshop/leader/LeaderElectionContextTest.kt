package io.bluetape4k.workshop.leader

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.leader.job.LeaderGuardedJob
import io.bluetape4k.workshop.leader.job.LeaderScheduledJobService
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * T0: Spring Boot context loading test.
 *
 * Verifies that the full application context starts correctly with all leader election beans.
 * Catches `@Component` omissions, `Duration` type mismatches, and `@ConfigurationProperties`
 * binding failures at runtime before they reach production.
 *
 * Uses `@DynamicPropertySource` to inject the Testcontainers Redis URL — the
 * `@TestPropertySource(properties = ["...${redis.port}"])` pattern is broken for this use case.
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
        (jobs.size >= 2).shouldBeTrue()  // at least CacheWarmupJob + StaleWorkflowCleanupJob
    }
}
