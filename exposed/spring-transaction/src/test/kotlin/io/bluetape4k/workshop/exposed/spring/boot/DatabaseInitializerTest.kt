package io.bluetape4k.workshop.exposed.spring.boot

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.exposed.spring.boot.tables.TestTable
import io.bluetape4k.workshop.exposed.spring.boot.tables.ignore.IgnoreTable
import org.amshove.kluent.internal.assertFailsWith
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.spring.boot4.DatabaseInitializer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(
    classes = [Application::class],
    properties = ["spring.autoconfigure.exclude=org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration"]
)
class DatabaseInitializerTest {

    companion object: KLogging()

    @Autowired
    private val applicationContext: ApplicationContext = uninitialized()

    @Test
    fun `should create schema for TestTable and not for IgnoreTable`() {
        val db = Database.connect("jdbc:h2:mem:test-spring", driver = "org.h2.Driver", user = "sa")
        transaction(db) {
            // `IgnoreTable` 은 `DatabaseInitializer` 에서 제외되어야 한다.
            val excludedPackages = listOf(DatabaseInitializerTest::class.java.`package`.name + ".tables.ignore")
            log.debug { "Excluded packages: $excludedPackages" }
            DatabaseInitializer(applicationContext, excludedPackages).run(DefaultApplicationArguments())

            TestTable.selectAll().count().toInt() shouldBeEqualTo 0

            // `IgnoreTable` 은 `DatabaseInitializer` 에서 제외되어 Table 생성이 되지 않는다.
            assertFailsWith<ExposedSQLException> {
                IgnoreTable.selectAll().count()
            }
        }
    }
}
