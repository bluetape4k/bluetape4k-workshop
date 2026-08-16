package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.idempotency.PollResult
import io.bluetape4k.workshop.operations.jobconsole.idempotency.JobSubmissionIdempotencyPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class BoundedConnectionAcquirerTest {

    @Test
    fun `late pool connection is closed after acquisition deadline`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val dataSource =
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
                                release.await(50, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                                false
                            }
                    }
                    return@newProxyInstance connectionProxy(closed)
                }
                defaultValue(method.returnType)
            } as DataSource

        val acquirer = BoundedConnectionAcquirer(dataSource)
        check(acquirer.acquire(Duration.ofMillis(25)) == null)
        check(entered.await(1, TimeUnit.SECONDS))

        release.countDown()
        check(closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `poll returns still in flight when pool acquisition consumes its budget`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val dataSource = blockingDataSource(entered, release, closed)
        val policy =
            JobSubmissionIdempotencyPolicy(
                connectionAcquireTimeout = Duration.ofMillis(25),
                statementTimeout = Duration.ofMillis(50),
            )
        val repository = JdbcJobSubmissionIdempotencyRepository(dataSource, JobRepository(dataSource), policy)

        repository.poll(DemoCallerScope("tenant", "submitter"), "a".repeat(64), 1, Instant.EPOCH, Duration.ofMillis(50)) shouldBeEqualTo
            PollResult.StillInFlight
        check(entered.await(1, TimeUnit.SECONDS))

        release.countDown()
        check(closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `poll does not run its query after setup consumes the deadline`() {
        val queryReached = AtomicBoolean(false)
        val aborted = CountDownLatch(1)
        val rolledBack = CountDownLatch(1)
        val connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "abort" -> {
                        aborted.countDown()
                        null
                    }
                    "rollback" -> {
                        rolledBack.countDown()
                        null
                    }
                    "createStatement" ->
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
                    else -> defaultValue(method.returnType)
                }
            } as Connection
        val dataSource =
            Proxy.newProxyInstance(
                DataSource::class.java.classLoader,
                arrayOf(DataSource::class.java),
            ) { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            } as DataSource
        val policy =
            JobSubmissionIdempotencyPolicy(
                connectionAcquireTimeout = Duration.ofMillis(100),
                statementTimeout = Duration.ofMillis(100),
            )
        val repository = JdbcJobSubmissionIdempotencyRepository(dataSource, JobRepository(dataSource), policy)

        repository.poll(DemoCallerScope("tenant", "submitter"), "a".repeat(64), 1, Instant.EPOCH, Duration.ofMillis(25)) shouldBeEqualTo
            PollResult.StillInFlight
        queryReached.get() shouldBeEqualTo false
        check(aborted.await(1, TimeUnit.SECONDS))
        check(!rolledBack.await(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `poll propagates an immediate connection failure instead of treating it as a deadline`() {
        val statement =
            Proxy.newProxyInstance(
                Statement::class.java.classLoader,
                arrayOf(Statement::class.java),
            ) { _, method, _ -> defaultValue(method.returnType) } as Statement
        val connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "createStatement" -> statement
                    "prepareStatement" -> throw SQLException("connection reset", "08006")
                    else -> defaultValue(method.returnType)
                }
            } as Connection
        val dataSource =
            Proxy.newProxyInstance(
                DataSource::class.java.classLoader,
                arrayOf(DataSource::class.java),
            ) { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            } as DataSource
        val policy =
            JobSubmissionIdempotencyPolicy(
                connectionAcquireTimeout = Duration.ofMillis(100),
                statementTimeout = Duration.ofMillis(100),
            )
        val repository = JdbcJobSubmissionIdempotencyRepository(dataSource, JobRepository(dataSource), policy)

        val failure =
            assertThrows<SQLException> {
                repository.poll(
                    DemoCallerScope("tenant", "submitter"),
                    "a".repeat(64),
                    1,
                    Instant.EPOCH,
                    Duration.ofMillis(100),
                )
            }
        failure.sqlState shouldBeEqualTo "08006"
    }

    @Test
    fun `poll maps postgres query cancellation to still in flight`() {
        val statement =
            Proxy.newProxyInstance(
                Statement::class.java.classLoader,
                arrayOf(Statement::class.java),
            ) { _, method, _ ->
                if (method.name == "execute") throw SQLException("statement timeout", "57014")
                defaultValue(method.returnType)
            } as Statement
        val connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                if (method.name == "createStatement") statement else defaultValue(method.returnType)
            } as Connection
        val dataSource =
            Proxy.newProxyInstance(
                DataSource::class.java.classLoader,
                arrayOf(DataSource::class.java),
            ) { _, method, _ ->
                if (method.name == "getConnection") connection else defaultValue(method.returnType)
            } as DataSource
        val policy =
            JobSubmissionIdempotencyPolicy(
                connectionAcquireTimeout = Duration.ofMillis(100),
                statementTimeout = Duration.ofMillis(100),
            )
        val repository = JdbcJobSubmissionIdempotencyRepository(dataSource, JobRepository(dataSource), policy)

        repository.poll(
            DemoCallerScope("tenant", "submitter"),
            "a".repeat(64),
            1,
            Instant.EPOCH,
            Duration.ofMillis(100),
        ) shouldBeEqualTo PollResult.StillInFlight
    }

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
                            release.await(50, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            false
                        }
                }
                return@newProxyInstance connectionProxy(closed)
            }
            defaultValue(method.returnType)
        } as DataSource

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
            else -> null
        }
}
