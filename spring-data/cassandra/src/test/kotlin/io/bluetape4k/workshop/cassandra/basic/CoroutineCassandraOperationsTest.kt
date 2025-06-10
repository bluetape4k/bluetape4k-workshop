package io.bluetape4k.workshop.cassandra.basic

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.querybuilder.QueryBuilder
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.insertInto
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.selectFrom
import io.bluetape4k.cassandra.querybuilder.literal
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.spring.cassandra.suspendExecute
import io.bluetape4k.spring.cassandra.suspendInsert
import io.bluetape4k.spring.cassandra.suspendSelect
import io.bluetape4k.spring.cassandra.suspendSelectOneById
import io.bluetape4k.spring.cassandra.suspendSelectOneOrNull
import io.bluetape4k.spring.cassandra.suspendUpdate
import io.bluetape4k.workshop.cassandra.AbstractCassandraCoroutineTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.cassandra.core.AsyncCassandraOperations
import org.springframework.data.cassandra.core.AsyncCassandraTemplate

@SpringBootTest(classes = [BasicConfiguration::class])
class CoroutineCassandraOperationsTest(
    @Autowired private val cqlSession: CqlSession,
): AbstractCassandraCoroutineTest("basic-user-ops") {

    companion object: KLoggingChannel() {
        private const val USER_TABLE = "basic_users"
    }

    // NOTE: AsyncCassandraTemplate 는 직접 Injection 받을 수 없고, 이렇게 생성해야 한다.
    private val operations: AsyncCassandraOperations by lazy { AsyncCassandraTemplate(cqlSession) }

    @BeforeEach
    fun setup() {
        runBlocking(Dispatchers.IO) {
            operations.suspendExecute(QueryBuilder.truncate(USER_TABLE).build())
        }
    }

    @Test
    fun `insert and select in coroutines`() = runSuspendIO {
        val insertStmt = insertInto(USER_TABLE)
            .value("user_id", 42L.literal())
            .value("uname", "debop".literal())
            .value("fname", "Debop".literal())
            .value("lname", "Bae".literal())
            .ifNotExists()
            .build()

        operations.suspendExecute(insertStmt)

        val user = operations.suspendSelectOneById<BasicUser>(42L)!!
        user.username shouldBeEqualTo "debop"

        val users = operations.suspendSelect<BasicUser>(selectFrom(USER_TABLE).all().build())
        users shouldBeEqualTo listOf(user)
    }

    @Test
    fun `insert and update`() = runSuspendIO {
        val user = newBasicUser(42L)
        operations.suspendInsert(user)

        val updated = user.copy(firstname = faker.name().firstName())
        operations.suspendUpdate(updated)

        val loaded = operations.suspendSelectOneById<BasicUser>(user.id)!!
        loaded shouldBeEqualTo updated
    }

    @Test
    fun `insert in coroutines`() = runSuspendIO {
        val users = List(100) {
            newBasicUser(it.toLong())
        }

        val tasks = users.map {
            async(Dispatchers.IO) {
                operations.suspendInsert(it)
            }
        }
        tasks.awaitAll()
    }

    @Test
    fun `select async projections`() = runSuspendIO {
        val user = newBasicUser(42L)
        operations.suspendInsert(user)

        val id = operations.suspendSelectOneOrNull<Long>(selectFrom(USER_TABLE).column("user_id").build())!!
        id.shouldNotBeNull().shouldBeEqualTo(user.id)

        val row = operations.suspendSelectOneOrNull<Row>(selectFrom(USER_TABLE).column("user_id").asCql())
        row.shouldNotBeNull()
        row.getLong(0) shouldBeEqualTo user.id

        val map = operations.suspendSelectOneOrNull<Map<*, *>>(selectFrom(USER_TABLE).all().limit(1).asCql())

        map.shouldNotBeNull()
        map["user_id"] shouldBeEqualTo user.id
        map["uname"] shouldBeEqualTo user.username
        map["fname"] shouldBeEqualTo user.firstname
        map["lname"] shouldBeEqualTo user.lastname
    }

    private fun newBasicUser(id: Long): BasicUser {
        return BasicUser(
            id = id,
            username = faker.internet().username(),
            firstname = faker.name().firstName(),
            lastname = faker.name().lastName()
        )
    }
}
