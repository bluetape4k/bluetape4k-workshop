package io.bluetape4k.workshop.exposed.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.User
import io.bluetape4k.workshop.exposed.domain.UserId
import io.bluetape4k.workshop.exposed.domain.UserTable
import io.bluetape4k.workshop.exposed.domain.toUser
import io.bluetape4k.workshop.exposed.dto.UserCreateRequest
import io.bluetape4k.workshop.exposed.dto.UserUpdateRequest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
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

        return UserTable.selectAll().map { it.toUser() }
    }

    @Transactional(readOnly = true)
    fun findUserById(id: UserId): User? {
        log.debug { "find user by id: ${id.value}" }

        return UserTable.selectAll()
            .where { UserTable.id eq id.value }
            .firstOrNull()
            ?.toUser()
    }

    /**
     * 사용자 정보를 생성합니다.
     */
    fun create(request: UserCreateRequest): UserId {
        val id = UserTable.insertAndGetId { entity ->
            entity[UserTable.name] = request.name
            entity[UserTable.age] = request.age
        }

        return UserId(id.value)
    }

    /**
     * 사용자 정보를 수정합니다.
     */
    fun update(userId: UserId, request: UserUpdateRequest): Int {
        return UserTable.update({ UserTable.id eq userId.value }) { entity ->
            request.name?.let { entity[UserTable.name] = it }
            request.age?.let { entity[UserTable.age] = it }
        }
    }

    /**
     * 사용자 정보를 삭제합니다.
     */
    fun delete(userId: UserId): Int {
        return UserTable.deleteWhere { UserTable.id eq userId.value }
    }
}
