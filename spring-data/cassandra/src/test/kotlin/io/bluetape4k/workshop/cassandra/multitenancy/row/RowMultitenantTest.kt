package io.bluetape4k.workshop.cassandra.multitenancy.row

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.cassandra.AbstractCassandraCoroutineTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [RowMultitenantTestConfiguration::class])
class RowMultitenantTest(
    @Autowired private val repository: EmployeeRepository,
): AbstractCassandraCoroutineTest("mt-table") {

    companion object: KLogging() {
        private const val REPEAT_TIMES = 5
    }

    private val employees = listOf(
        Employee("apple", "Debop"),
        Employee("apple", "Steve"),
        Employee("amazon", "Jeff"),
    )

    @BeforeEach
    fun beforeEach() = runSuspendIO {
        repository.deleteAll()

        val saved = repository.saveAll(employees.asFlow()).toList()
        saved.size shouldBeEqualTo employees.size
    }

    @Test
    fun `find all by tenantId and name`() = runSuspendIO {

        // tenant 정보를 제공하여 tenantId 를 검색 조건에 들도록 합니다.
        TenantIdProvider.tenantId.set("apple")
        val job1 = launch(Dispatchers.IO + TenantIdProvider.tenantId.asContextElement()) {
            repeat(REPEAT_TIMES) {
                val loaded = repository.findAllByName("Steve").toList()

                loaded.size shouldBeEqualTo 1
                loaded.first() shouldBeEqualTo Employee("apple", "Steve")
            }
        }

        // tenant 정보를 제공하여 tenantId 를 검색 조건에 들도록 합니다.
        TenantIdProvider.tenantId.set("amazon")
        val job2 = launch(Dispatchers.IO + TenantIdProvider.tenantId.asContextElement()) {
            repeat(REPEAT_TIMES) {
                val loaded = repository.findAllByName("Steve").toList()
                loaded.shouldBeEmpty()
            }
        }

        // 이렇게 해도 되지만 `coroutineScope` 를 사용하면 좀 더 체계적으로 예외까지 관리할 수 있습니다.
        runCatching { job1.join() }
        runCatching { job2.join() }
    }
}
