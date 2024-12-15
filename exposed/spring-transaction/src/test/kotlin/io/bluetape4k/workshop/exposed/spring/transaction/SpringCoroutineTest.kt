package io.bluetape4k.workshop.exposed.spring.transaction

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.debug.junit5.CoroutinesTimeout
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
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
    open fun `test nested coroutine transaction`() = runTest {
        try {
            newSuspendedTransaction(Dispatchers.IO) {
                log.debug { "Create schema ..." }
                SchemaUtils.create(Testing)
            }

            val mainJob = async(Dispatchers.IO) {
                val results = List(5) { indx ->
                    suspendedTransactionAsync(Dispatchers.IO) {
                        Testing.insert { }
                        indx
                    }
                }.awaitAll()

                results.sum() shouldBeEqualTo 10
            }

            await atMost Duration.ofSeconds(60) until { mainJob.isCompleted }

            mainJob.getCompletionExceptionOrNull()?.let { throw it }

            newSuspendedTransaction(Dispatchers.IO) {
                log.debug { "Load Testing entities ..." }
                Testing.selectAll().count() shouldBeEqualTo 5L
            }
        } finally {
            newSuspendedTransaction(Dispatchers.IO) {
                log.debug { "Drop schema ..." }
                SchemaUtils.drop(Testing)
            }
        }
    }
}
