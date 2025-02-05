package io.bluetape4k.workshop.exposed.domain.h2

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.dao.idValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

class ConnectionPoolTest {

    companion object: KLogging()

    private val hikariDataSource1 by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:hikariDB1"
                maximumPoolSize = 10
            }
        )
    }

    private val hikariDB1 by lazy {
        Database.connect(hikariDataSource1)
    }

    @Test
    fun `suspend transactions exceeding pool size`() {
        Assumptions.assumeTrue(TestDB.H2 in TestDB.enabledDialects())

        transaction(db = hikariDB1) {
            SchemaUtils.create(TestTable)
        }

        // DataSource의 maximumPoolSize를 초과하는 트랜잭션을 실행합니다.
        val exceedsPoolSize = (hikariDataSource1.maximumPoolSize * 2 + 1).coerceAtMost(50)
        log.debug { "Exceeds pool size: $exceedsPoolSize" }

        runBlocking {
            repeat(exceedsPoolSize) {
                launch {
                    newSuspendedTransaction {
                        val entity = TestEntity.new { testValue = "test$it" }
                        log.debug { "Created test entity: $entity" }
                    }
                }
            }
        }

        transaction(db = hikariDB1) {
            TestEntity.all().count() shouldBeEqualTo exceedsPoolSize.toLong()
            SchemaUtils.drop(TestTable)
        }
    }

    object TestTable: IntIdTable("HIKARI_TESTER") {
        val testValue = varchar("test_value", 32)
    }

    class TestEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TestEntity>(TestTable)

        var testValue by TestTable.testValue

        override fun toString(): String = "TestEntity(id=$idValue, testValue=$testValue)"
    }
}
