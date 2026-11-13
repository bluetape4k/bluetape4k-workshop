package io.bluetape4k.workshop.mongodb.reactive

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.mongodb.domain.Person
import io.bluetape4k.workshop.mongodb.domain.PersonReactiveRepository
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.ReactiveMongoOperations
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PersonReactiveRepositoryTest @Autowired constructor(
    private val repository: PersonReactiveRepository,
    operations: ReactiveMongoOperations,
): AbstractReactiveMongoTest(operations) {

    companion object: KLoggingChannel()

    @Test
    fun `insert and count`() {
        val prevCount = repository.count().block().shouldNotBeNull()

        val saveAndCount = repository.count()
            .thenMany(
                repository.saveAll(Flux.just(newPerson(), newPerson()))
            )
            .last()
            .flatMap { repository.count() }

        StepVerifier.create(saveAndCount).expectNext(prevCount + 2).verifyComplete()
    }

    @Test
    fun `perform conversion before result processing`() {
        StepVerifier.create(repository.findAll())
            .expectNextCount(4L)
            .verifyComplete()
    }

    @Test
    fun `stream data with tailable cursor`() {
        val prevCount = repository.count().block().shouldNotBeNull()

        val queue = ConcurrentLinkedQueue<Person>()

        val disposable = repository.findWithTailableCursorBy()
            .doOnNext(queue::add)
            .subscribe()

        await atMost 1.seconds until { queue.size >= prevCount }

        StepVerifier.create(repository.save(newPerson()))
            .expectNextCount(1)
            .verifyComplete()

        await atMost 1.seconds until { queue.size >= prevCount + 1 }

        StepVerifier.create(repository.save(newPerson()))
            .expectNextCount(1)
            .verifyComplete()

        await atMost 1.seconds until { queue.size >= prevCount + 2 }

        // dispose 된 이후로 추가된 Person은 queue에 추가되지 않는다
        disposable.dispose()

        StepVerifier.create(repository.save(newPerson()))
            .expectNextCount(1)
            .verifyComplete()

        await atMost 500.milliseconds withPollInterval 100.milliseconds until {
            queue.size == (prevCount.toInt() + 2)
        }

        queue.size shouldBeEqualTo prevCount.toInt() + 2
    }

    @Test
    fun `query data with query derivation`() {
        StepVerifier.create(repository.findByLastname("White")).expectNextCount(2).verifyComplete()
    }

    @Test
    fun `query data with string query`() {
        StepVerifier.create(repository.findByFirstnameAndLastname("Walter", "White")).expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `query data with deferred query deviation`() {
        StepVerifier.create(repository.findByLastname(Mono.just("White"))).expectNextCount(2).verifyComplete()
    }

    @Test
    fun `query data with mixed diferred query deviation`() {
        val person = repository.findByFirstnameAndLastname(Mono.just("Walter"), "White")
        StepVerifier.create(person).expectNextCount(1).verifyComplete()
    }
}
