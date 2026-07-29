package io.bluetape4k.workshop.leader

import io.bluetape4k.workshop.leader.config.LeaderElectionProperties
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration
import io.bluetape4k.assertions.assertFailsWith

/**
 * P3-13: [LeaderElectionProperties] `init {}` validation test입니다.
 *
 * 잘못된 configuration(leaseTime < waitTime)이 construction time에 거부되는지 검증합니다.
 * Spring context나 Redis가 필요 없는 순수 unit test입니다.
 */
class PropertiesValidationTest {

    @Test
    fun `leaseTime less than waitTime throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderElectionProperties(
                waitTime = Duration.ofSeconds(30),
                leaseTime = Duration.ofSeconds(10),  // leaseTime < waitTime — invalid
            )
        }
    }

    @Test
    fun `valid properties construction succeeds`() {
        val props = LeaderElectionProperties(
            waitTime = Duration.ofSeconds(2),
            leaseTime = Duration.ofSeconds(30),
        )
        props.waitTime.toSeconds() shouldBeEqualTo 2L
        props.leaseTime.toSeconds() shouldBeEqualTo 30L
    }

    @Test
    fun `equal waitTime and leaseTime is valid`() {
        val props = LeaderElectionProperties(
            waitTime = Duration.ofSeconds(10),
            leaseTime = Duration.ofSeconds(10),
        )
        props.waitTime shouldBeEqualTo props.leaseTime
    }
}
