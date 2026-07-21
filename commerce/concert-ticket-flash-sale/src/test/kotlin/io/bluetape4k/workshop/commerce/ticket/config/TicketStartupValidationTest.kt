package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

internal class TicketStartupValidationTest {
    @Test
    fun `unknown configuration properties are rejected`() {
        val annotation = TicketProperties::class.java.getAnnotation(ConfigurationProperties::class.java)

        annotation.ignoreUnknownFields.shouldBeFalse()
    }

    @Test
    fun `database lanes leave two hikari connections reserved`() {
        val failure =
            assertFailsWith<TicketStartupException> {
                TicketStartupValidator.validate(
                    TicketProperties(
                        db =
                            TicketDatabaseProperties(
                                maxPoolSize = 20,
                                foregroundPermits = 16,
                                workerPermits = 3,
                                ssePermits = 2,
                                operatorPermits = 1,
                            ),
                    ),
                )
            }

        failure.code shouldBeEqualTo TicketStartupFailure.INVALID_DATABASE_CAPACITY
        failure.message shouldBeEqualTo TicketStartupFailure.INVALID_DATABASE_CAPACITY.name
    }

    @Test
    fun `redis lease timing leaves room for renewal`() {
        val failure =
            assertFailsWith<TicketStartupException> {
                TicketStartupValidator.validate(
                    TicketProperties(
                        redis =
                            TicketRedisProperties(
                                commandTimeout = Duration.ofSeconds(2),
                                renewInterval = Duration.ofSeconds(2),
                                leaseTtl = Duration.ofSeconds(5),
                            ),
                    ),
                )
            }

        failure.code shouldBeEqualTo TicketStartupFailure.INVALID_REDIS_LEASE_TIMING
    }

    @Test
    fun `redis route limit is finite and explicitly configured`() {
        val failure =
            assertFailsWith<TicketStartupException> {
                TicketStartupValidator.validate(
                    TicketProperties(
                        redis = TicketRedisProperties(rateLimitCapacity = 0),
                    ),
                )
            }

        failure.code shouldBeEqualTo TicketStartupFailure.INVALID_REDIS_CONFIGURATION
    }
}
