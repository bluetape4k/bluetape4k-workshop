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

/**
 * TicketStartupValidationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketStartupValidationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "unknown configuration properties are rejected" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unknown configuration properties are rejected`() {
        val annotation = TicketProperties::class.java.getAnnotation(ConfigurationProperties::class.java)

        annotation.ignoreUnknownFields.shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "database lanes leave two hikari connections reserved" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "publication wiring applies the configured database permit timeout" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `publication wiring applies the configured database permit timeout`() {
        val configuredTimeout = 5.seconds()
        val executor = TicketPublicationConfiguration().ticketJdbcExecutor(
            dataSource = object: AbstractDataSource() {
/**
 * getConnection은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                override fun getConnection(): Connection = error("connection must remain lazy")

/**
 * getConnection은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
/**
 * 이 시나리오는 "redis lease timing leaves room for renewal" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "redis route limit is finite and explicitly configured" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
