package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.datasource.AbstractDataSource
import java.sql.Connection

internal class TicketStartupValidationTest {

    companion object: KLogging()

    @Test
    fun `unknown configuration properties are rejected`() {
        val annotation = TicketProperties::class.java.getAnnotation(ConfigurationProperties::class.java)

        annotation.ignoreUnknownFields.shouldBeFalse()
    }

    @Test
    fun `database lanes leave two hikari connections reserved`() {
        val failure = assertFailsWith<TicketStartupException> {
            TicketStartupValidator.validate(
                TicketProperties(
                    db = TicketDatabaseProperties(
                        maxPoolSize = 20,
                        foregroundPermits = 16,
                        workerPermits = 3,
                        ssePermits = 2,
                        operatorPermits = 1,
                    ),
                ),
            )
        }

        log.debug { "failure=$failure" }
        failure.code shouldBeEqualTo TicketStartupFailure.INVALID_DATABASE_CAPACITY
        failure.message shouldBeEqualTo TicketStartupFailure.INVALID_DATABASE_CAPACITY.name
    }

    @Test
    fun `publication wiring applies the configured database permit timeout`() {
        val configuredTimeout = 5.seconds()
        val executor = TicketPublicationConfiguration().ticketJdbcExecutor(
            dataSource = object: AbstractDataSource() {
                override fun getConnection(): Connection = error("connection must remain lazy")

                override fun getConnection(username: String?, password: String?): Connection =
                    error("connection must remain lazy")
            },
            properties = TicketProperties(
                db = TicketDatabaseProperties(permitTimeout = configuredTimeout),
            ),
        )

        executor.permitTimeout shouldBeEqualTo configuredTimeout
    }

    @Test
    fun `redis lease timing leaves room for renewal`() {
        val failure =
            assertFailsWith<TicketStartupException> {
                TicketStartupValidator.validate(
                    TicketProperties(
                        redis =
                            TicketRedisProperties(
                                commandTimeout = 2.seconds(),
                                renewInterval = 2.seconds(),
                                leaseTtl = 5.seconds(),
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
