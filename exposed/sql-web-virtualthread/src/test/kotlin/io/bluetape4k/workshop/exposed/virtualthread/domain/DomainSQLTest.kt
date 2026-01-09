package io.bluetape4k.workshop.exposed.virtualthread.domain

import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.utils.Runtimex
import io.bluetape4k.workshop.exposed.virtualthread.AbstractExposedTest
import io.bluetape4k.workshop.exposed.virtualthread.domain.mapper.toActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Actor
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Actors
import org.amshove.kluent.shouldNotBeEmpty
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnJre
import org.junit.jupiter.api.condition.JRE
import org.springframework.beans.factory.annotation.Autowired

class DomainSQLTest(
    @param:Autowired private val db: Database,
): AbstractExposedTest() {

    companion object: KLoggingChannel() {
        private const val REPEAT_SIZE = 5
    }

    @Nested
    inner class PlatformThread {

        @RepeatedTest(REPEAT_SIZE)
        fun `get all actors in platform threads`() {
            transaction {
                val actors = Actors.selectAll().map { it.toActorDTO() }
                actors.shouldNotBeEmpty()
            }
        }

        @Test
        fun `get all actors in multiple platform threads`() {
            MultithreadingTester()
                .numThreads(Runtimex.availableProcessors * 2)
                .roundsPerThread(4)
                .add {
                    transaction(db) {
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
            virtualFuture {
                transaction {
                    val actors = Actors.selectAll().map { it.toActorDTO() }
                    actors.shouldNotBeEmpty()
                }
            }.await()
        }

        @EnabledOnJre(JRE.JAVA_21)
        @Test
        fun `get all actors in multiple virtual threads`() {
            StructuredTaskScopeTester()
                .roundsPerTask(Runtimex.availableProcessors * 2 * 4)
                .add {
                    transaction(db) {
                        val actors = Actor.all().toList()
                        // val actors = Actors.selectAll().map { it.toActorDTO() }
                        actors.shouldNotBeEmpty()
                    }
                }
                .run()
        }
    }
}
