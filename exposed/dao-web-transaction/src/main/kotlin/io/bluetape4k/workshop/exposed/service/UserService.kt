package io.bluetape4k.workshop.exposed.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.User
import io.bluetape4k.workshop.exposed.domain.UserEntity
import io.bluetape4k.workshop.exposed.domain.UserId
import io.bluetape4k.workshop.exposed.domain.UserTable
import io.bluetape4k.workshop.exposed.dto.UserCreateRequest
import io.bluetape4k.workshop.exposed.dto.UserUpdateRequest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class UserService {

    companion object: KLogging()

    @Transactional(readOnly = true)
    fun findAllUsers(): List<User> {
        log.debug { "find all users" }

        return UserEntity.all().map { it.toUser() }
        // return UserTable.selectAll().map { it.toUser() }
    }

    @Transactional(readOnly = true)
    fun findUserById(id: UserId): User? {
        log.debug { "find user by id: ${id.value}" }

        return UserEntity.findById(id.value)?.toUser()

//        return UserTable.selectAll()
//            .where { UserTable.id eq id.value }
//            .firstOrNull()
//            ?.toUser()
    }

    /**
     * 사용자 정보를 생성합니다.
     */
    fun create(request: UserCreateRequest): UserId {
        val newUser = UserEntity.new {
            name = request.name
            age = request.age
        }
        return UserId(newUser.id.value)

//        val id = UserTable.insertAndGetId {
//            it[UserTable.name] = request.name
//            it[UserTable.age] = request.age
//        }
//
//        return UserId(id.value)
    }

    /**
     * 복수의 사용자를 Batch 방식으로 생성합니다.
     */
    fun createBatch(requests: List<UserCreateRequest>): List<UserId> {
        val rows = UserTable.batchInsert(requests) { request ->
            this[UserTable.name] = request.name
            this[UserTable.age] = request.age
        }
        return rows.map { UserId(it[UserTable.id].value) }
    }

    /**
     * 사용자 정보를 수정합니다.
     */
    fun update(userId: UserId, request: UserUpdateRequest): Int {
        return UserTable.update({ UserTable.id eq userId.value }) { users ->
            request.name?.run { users[UserTable.name] = this }
            request.age?.run { users[UserTable.age] = this }
        }
    }

    /**
     * 사용자 정보를 삭제합니다.
     */
    fun delete(userId: UserId): Int {
        return UserTable.deleteWhere { UserTable.id eq userId.value }
    }
}
