package io.bluetape4k.workshop.cassandra.reactive.multitenant

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.cassandra.AbstractCassandraCoroutineTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import reactor.util.context.Context

@SpringBootTest(classes = [RowLevelMultitenantTestConfiguration::class])
class RowLevelMultitenantTest(
    @Autowired private val repository: EmployeeRepository,
): AbstractCassandraCoroutineTest("row_level_multitenancy") {

    companion object: KLogging()

    val employees = listOf(
        Employee("breaking-bad", "Walter"),
        Employee("breaking-bad", "Hank"),
        Employee("south-park", "Hank")
    )

    @BeforeEach
    fun setup() = runSuspendIO {
        repository.deleteAll().awaitSingleOrNull()

        val saved = repository.saveAll(employees).asFlow().toList()
        saved.size shouldBeEqualTo employees.size
    }

    // FIXME: 이 테스트는 실패합니다. 기존 Driver 를 사용하면 통과합니다. (oss 버전)
    @Disabled("이 테스트는 실패합니다. 기존 Driver 를 사용하면 통과합니다. (oss 버전)")
    @Test
    fun `should find by tenantId and name`() = runSuspendIO {

        // tenant 정보를 제공하여 처리하도록 한다
        val loaded = repository.findAllByName("Hank")
            .contextWrite(Context.of(Tenant::class.java, Tenant("breaking-bad")))
            .asFlow()
            .toList()

        loaded.size shouldBeEqualTo 2
        loaded.first().tenantId shouldBeEqualTo "breaking-bad"
    }
}
