package io.bluetape4k.workshop.exposed.r2dbc.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.repository.UserExposedRepository
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.springframework.stereotype.Service

@Service
class UserService(private val repository: UserExposedRepository) {

    companion object: KLoggingChannel()

    suspend fun findAll(): List<UserRecord> = suspendTransaction {
        repository.findAll().toList()
    }

    suspend fun findByIdOrNull(id: Int): UserRecord? = suspendTransaction {
        repository.findByIdOrNull(id)
    }

    suspend fun findByEmail(email: String): List<UserRecord> = suspendTransaction {
        repository.findByEmail(email).toList()
    }

    suspend fun addUser(user: UserRecord): UserRecord? = suspendTransaction {
        log.debug { "Save new user. $user" }
        val newId = repository.save(user)
        user.withId(newId.value)
    }

    suspend fun updateUser(id: Int, user: UserRecord): UserRecord? = suspendTransaction {
        val count = repository.update(user.withId(id))
        if (count > 0) user else null
    }

    suspend fun deleteUser(id: Int): Boolean = suspendTransaction {
        repository.deleteById(id) > 0
    }
}
