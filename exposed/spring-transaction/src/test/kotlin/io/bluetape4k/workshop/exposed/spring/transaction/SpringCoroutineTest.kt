package io.bluetape4k.workshop.exposed.spring.transaction

import io.bluetape4k.junit5.awaitility.coUntil
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.debug.junit5.CoroutinesTimeout
import org.amshove.kluent.shouldBeEqualTo
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.experimental.suspendedTransactionAsync
import org.junit.jupiter.api.RepeatedTest
import org.springframework.test.annotation.Commit
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

open class SpringCoroutineTest: SpringTransactionTestBase() {

    companion object: KLogging() {
        private val timeout = CoroutinesTimeout(60_000)
        private const val REPEAT_SIZE = 5
    }

    object Testing: Table("COROUTINE_TESTING") {
        val id = integer("id").autoIncrement()
        override val primaryKey = PrimaryKey(id)
    }

    @RepeatedTest(REPEAT_SIZE)
    @Transactional
    @Commit
    open fun `test nested coroutine transaction`() = runSuspendIO {
        try {
            newSuspendedTransaction {
                log.debug { "Create schema ..." }
                SchemaUtils.create(Testing)
            }

            val mainJob = async {
                val results = List(5) { indx ->
                    suspendedTransactionAsync {
                        Testing.insert { }
                        indx
                    }
                }.awaitAll()

                results.sum() shouldBeEqualTo 10
            }

            // Main Job 을 60초까지 기다린다.
            await atMost Duration.ofSeconds(60) coUntil { mainJob.isCompleted }

            mainJob.getCompletionExceptionOrNull()?.let { throw it }

            newSuspendedTransaction {
                log.debug { "Load Testing entities ..." }
                Testing.selectAll().count() shouldBeEqualTo 5L
            }
        } finally {
            newSuspendedTransaction {
                log.debug { "Drop schema ..." }
                SchemaUtils.drop(Testing)
            }
        }
    }
}
