package io.bluetape4k.workshop.observability.advanced.repository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.observability.advanced.model.User
import io.bluetape4k.workshop.observability.advanced.model.Users
import io.bluetape4k.workshop.observability.advanced.model.toUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository

/**
 * Exposed JDBC 를 사용해 [User] 를 persist 하는 repository 입니다.
 *
 * ## Behavior / Contract
 * - 모든 method 는 Exposed 호출을 `withContext(Dispatchers.IO) { transaction { ... } }` 으로 감쌉니다.
 * - Spring `@Transactional` 은 의도적으로 피합니다. connection 을 thread-local 에 bind 하므로 `withContext(Dispatchers.IO)` 가 coroutine 을 다른 thread 로 전환할 때 깨질 수 있습니다.
 * - 여기서는 Observation span 을 만들지 않습니다. instrumentation 은 service layer 책임입니다.
 * - `org.jetbrains.exposed.v1.core` 의 top-level `eq` 를 사용합니다.
 */
@Repository
class UserRepository {

    companion object : KLogging()

    suspend fun findById(id: Long): User? = withContext(Dispatchers.IO) {
        val validId = id.requirePositiveNumber("id")
        transaction {
            Users.selectAll()
                .where { Users.id eq validId }
                .singleOrNull()
                ?.toUser()
        }
    }

    suspend fun save(user: User): Unit = withContext(Dispatchers.IO) {
        transaction {
            Users.insert {
                it[id] = user.id
                it[name] = user.name
                it[email] = user.email
            }
        }
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        transaction {
            Users.deleteAll()
        }
    }
}
