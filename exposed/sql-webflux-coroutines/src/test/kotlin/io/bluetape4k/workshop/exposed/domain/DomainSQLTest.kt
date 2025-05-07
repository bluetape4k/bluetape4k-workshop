package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.exposed.sql.transactions.newVirtualThreadTransaction
import io.bluetape4k.exposed.sql.transactions.virtualThreadTransactionAsync
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.utils.Runtimex
import io.bluetape4k.workshop.exposed.AbstractExposedSqlTest
import io.bluetape4k.workshop.exposed.domain.mapper.toActorDTO
import io.bluetape4k.workshop.exposed.domain.schema.Actors
import org.amshove.kluent.shouldNotBeEmpty
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

class DomainSQLTest: AbstractExposedSqlTest() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5
    }

    @Nested
    inner class Coroutines {

        @RepeatedTest(REPEAT_SIZE)
        fun `get all actors in coroutines`() = runSuspendIO {
            newSuspendedTransaction {
                val actors = Actors.selectAll().map { it.toActorDTO() }
                actors.shouldNotBeEmpty()
            }
        }

        @Test
        fun `get all actors in multiple coroutines`() = runSuspendIO {
            SuspendedJobTester()
                .numThreads(Runtimex.availableProcessors * 2)
                .roundsPerJob(Runtimex.availableProcessors * 8)
                .add {
                    newSuspendedTransaction {
                        // addLogger(StdOutSqlLogger)
                        val actors = Actors.selectAll().map { it.toActorDTO() }
                        actors.shouldNotBeEmpty()
                    }
                }
                .run()
        }
    }

    @Nested
    inner class VirtualThread {

        @RepeatedTest(REPEAT_SIZE)
        fun `get all actors in virtual threads`() {
            newVirtualThreadTransaction {
                val actors = Actors.selectAll().map { it.toActorDTO() }
                actors.shouldNotBeEmpty()
            }
        }

        @Test
        fun `get all actors in multiple virtual threads`() {
            StructuredTaskScopeTester()
                .roundsPerTask(Runtimex.availableProcessors * 2 * 4)
                .add {
                    val actors = virtualThreadTransactionAsync {
                        Actors.selectAll().map { it.toActorDTO() }
                    }.await()
                    actors.shouldNotBeEmpty()
                }
                .run()
        }
    }
}
