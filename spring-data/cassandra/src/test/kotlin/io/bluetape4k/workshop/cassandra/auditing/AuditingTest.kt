package io.bluetape4k.workshop.cassandra.auditing

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cassandra.AbstractCassandraCoroutineTest
import org.amshove.kluent.shouldBeAfter
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant

@SpringBootTest(classes = [AuditingTestConfiguration::class])
class AuditingTest(
    @Autowired private val repository: AuditedPersonRepository,
): AbstractCassandraCoroutineTest("auditing") {

    companion object: KLoggingChannel() {
        const val ACTOR = AuditingTestConfiguration.ACTOR
    }

    @BeforeEach
    fun setup() = runSuspendIO {
        repository.deleteAll()
    }

    @Test
    fun `insert audited person should set createdAt`() = runSuspendIO {
        val person = AuditedPerson(faker.random().nextLong(1, Long.MAX_VALUE))
        val saved = repository.save(person)

        saved.createdBy shouldBeEqualTo ACTOR
        saved.lastModifiedBy shouldBeEqualTo ACTOR

        val range = rangeOf()
        saved.createdAt.shouldNotBeNull() shouldBeInRange range
        saved.lastModifiedAt.shouldNotBeNull() shouldBeInRange range
    }

    @Test
    fun `update audited person should set lastModifiedAt`() = runSuspendIO {
        val person = AuditedPerson(faker.random().nextLong(1, Long.MAX_VALUE))
        val saved = repository.save(person)

        val modified = repository.save(saved)

        modified.createdBy shouldBeEqualTo ACTOR
        modified.lastModifiedBy shouldBeEqualTo ACTOR

        val range = rangeOf()
        modified.createdAt.shouldNotBeNull() shouldBeInRange range
        modified.lastModifiedAt.shouldNotBeNull() shouldBeInRange range
        modified.lastModifiedAt.shouldNotBeNull() shouldBeAfter modified.createdAt!!
    }

    private fun rangeOf(moment: Instant = Instant.now()): ClosedRange<Instant> {
        return moment.minusSeconds(60)..moment.plusSeconds(60)
    }
}
