package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PollResult
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** bounded-wait 중 connection pool/query cancellation이 남은 예산과 오류 종류를 정확히 반영하는지 검증한다. */
/** bounded-wait 중 connection pool/query cancellation이 남은 예산과 오류 종류를 정확히 반영하는지 검증한다. */
class BoundedConnectionAcquirerTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "late pool connection is closed after acquisition deadline" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `late pool connection is closed after acquisition deadline`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val dataSource = Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, _ ->
            if (method.name == "getConnection") {
                entered.countDown()
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * released은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                var released = false
                while (!released) {
                    released =
                        try {
                            release.await(50.milliseconds)
                        } catch (_: InterruptedException) {
                            false
                        }
                }
                return@newProxyInstance connectionProxy(closed)
            }
            defaultValue(method.returnType)
        } as DataSource

        val acquirer = BoundedConnectionAcquirer(dataSource)
        acquirer.acquire(25.millis()).shouldBeNull()
        entered.await(1.seconds).shouldBeTrue()

        release.countDown()
        closed.await(1.seconds).shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "poll returns still in flight when pool acquisition consumes its budget" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `poll returns still in flight when pool acquisition consumes its budget`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val dataSource = blockingDataSource(entered, release, closed)
        val policy = JobSubmissionIdempotencyPolicy(
            connectionAcquireTimeout = 25.millis(),
            statementTimeout = 50.millis(),
        )
        val repository = JdbcJobSubmissionIdempotencyRepository(dataSource, JobRepository(dataSource), policy)

        repository
            .poll(
                DemoCallerScope("tenant", "submitter"),
                "a".repeat(64),
                1,
                Instant.EPOCH,
                50.millis()
            ) shouldBeEqualTo PollResult.StillInFlight

        entered.await(1.seconds).shouldBeTrue()

        release.countDown()
        closed.await(1.seconds).shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "poll does not run its query after setup consumes the deadline" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `poll does not run its query after setup consumes the deadline`() {
        val queryReached = AtomicBoolean(false)
        val aborted = CountDownLatch(1)
        val rolledBack = CountDownLatch(1)
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "abort"            -> {
                    aborted.countDown()
                    null
                }
                "rollback"         -> {
                    rolledBack.countDown()
                    null
                }
                "createStatement"  ->
                    Proxy.newProxyInstance(
                        Statement::class.java.classLoader,
                        arrayOf(Statement::class.java),
                    ) { _, statementMethod, _ ->
                        if (statementMethod.name == "execute") {
                            Thread.sleep(60)
                            false
                        } else {
                            defaultValue(statementMethod.returnType)
                        }
                    }
                "prepareStatement" -> {
                    queryReached.set(true)
                    error("poll query must not start after setup deadline")
                }
                else               -> defaultValue(method.returnType)
            }
        } as Connection
        val dataSource = Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, _ ->
            if (method.name == "getConnection") connection else defaultValue(method.returnType)
        } as DataSource

        val policy = JobSubmissionIdempotencyPolicy(
            connectionAcquireTimeout = 100.millis(),
            statementTimeout = 100.millis(),
        )
        val repository = JdbcJobSubmissionIdempotencyRepository(dataSource, JobRepository(dataSource), policy)

        repository
            .poll(
                DemoCallerScope("tenant", "submitter"),
                "a".repeat(64),
                1,
                Instant.EPOCH,
                25.millis()
            ) shouldBeEqualTo PollResult.StillInFlight

        queryReached.get().shouldBeFalse()
        aborted.await(1.seconds).shouldBeTrue()
        rolledBack.await(100.milliseconds).shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "poll propagates an immediate connection failure instead of treating it as a deadline"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `poll propagates an immediate connection failure instead of treating it as a deadline`() {
        val statement = Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
        ) { _, method, _ -> defaultValue(method.returnType) } as Statement

        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "createStatement"  -> statement
                "prepareStatement" -> throw SQLException("connection reset", "08006")
                else               -> defaultValue(method.returnType)
            }
        } as Connection

        val dataSource = Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, _ ->
            if (method.name == "getConnection") connection else defaultValue(method.returnType)
        } as DataSource

        val policy = JobSubmissionIdempotencyPolicy(
            connectionAcquireTimeout = 100.millis(),
            statementTimeout = 100.millis(),
        )
        val repository = JdbcJobSubmissionIdempotencyRepository(dataSource, JobRepository(dataSource), policy)

        val failure = assertFailsWith<SQLException> {
            repository.poll(
                DemoCallerScope("tenant", "submitter"),
                "a".repeat(64),
                1,
                Instant.EPOCH,
                100.millis(),
            )
        }
        log.debug { "failure=$failure" }
        failure.sqlState shouldBeEqualTo "08006"
    }

    @Test
/**
 * 이 시나리오는 "poll maps postgres query cancellation to still in flight" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `poll maps postgres query cancellation to still in flight`() {
        val statement = Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
        ) { _, method, _ ->
            if (method.name == "execute") throw SQLException("statement timeout", "57014")
            defaultValue(method.returnType)
        } as Statement

        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            if (method.name == "createStatement") statement else defaultValue(method.returnType)
        } as Connection

        val dataSource = Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, _ ->
            if (method.name == "getConnection") connection else defaultValue(method.returnType)
        } as DataSource

        val policy = JobSubmissionIdempotencyPolicy(
            connectionAcquireTimeout = 100.millis(),
            statementTimeout = 100.millis(),
        )
        val repository = JdbcJobSubmissionIdempotencyRepository(dataSource, JobRepository(dataSource), policy)

        repository
            .poll(
                DemoCallerScope("tenant", "submitter"),
                "a".repeat(64),
                1,
                Instant.EPOCH,
                100.millis(),
            ) shouldBeEqualTo PollResult.StillInFlight
    }

/**
 * blockingDataSource은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun blockingDataSource(
        entered: CountDownLatch,
        release: CountDownLatch,
        closed: CountDownLatch,
    ): DataSource =
        Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, _ ->
            if (method.name == "getConnection") {
                entered.countDown()
                var released = false
                while (!released) {
                    released =
                        try {
                            release.await(50.milliseconds)
                        } catch (_: InterruptedException) {
                            false
                        }
                }
                return@newProxyInstance connectionProxy(closed)
            }
            defaultValue(method.returnType)
        } as DataSource

/**
 * connectionProxy은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun connectionProxy(closed: CountDownLatch): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            if (method.name == "close") {
                closed.countDown()
                null
            } else {
                defaultValue(method.returnType)
            }
        } as Connection

/**
 * defaultValue은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else                         -> null
        }
}
