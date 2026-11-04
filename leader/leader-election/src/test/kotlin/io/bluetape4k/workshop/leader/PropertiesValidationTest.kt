package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.leader.config.LeaderElectionProperties
import org.junit.jupiter.api.Test

/**
 * P3-13: [LeaderElectionProperties] `init {}` validation test입니다.
 *
 * 잘못된 configuration(leaseTime < waitTime)이 construction time에 거부되는지 검증합니다.
 * Spring context나 Redis가 필요 없는 순수 unit test입니다.
 */
class PropertiesValidationTest {

    companion object: KLogging()

    @Test
    fun `leaseTime less than waitTime throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderElectionProperties(
                waitTime = 30.seconds(),
                leaseTime = 10.seconds(),  // leaseTime < waitTime — invalid
            )
        }
    }

    @Test
    fun `valid properties construction succeeds`() {
        val props = LeaderElectionProperties(
            waitTime = 2.seconds(),
            leaseTime = 30.seconds(),
        )
        props.waitTime.toSeconds() shouldBeEqualTo 2L
        props.leaseTime.toSeconds() shouldBeEqualTo 30L
    }

    @Test
    fun `equal waitTime and leaseTime is valid`() {
        val props = LeaderElectionProperties(
            waitTime = 10.seconds(),
            leaseTime = 10.seconds(),
        )
        props.waitTime shouldBeEqualTo props.leaseTime
    }
}
