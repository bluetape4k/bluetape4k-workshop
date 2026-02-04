package io.bluetape4k.workshop.exposed.r2dbc.service

import io.bluetape4k.coroutines.flow.extensions.toFastList
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.repository.UserExposedRepository
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.springframework.stereotype.Service

@Service
class UserService(private val repository: UserExposedRepository) {

    companion object: KLoggingChannel()

    suspend fun findAll(): List<UserRecord> = suspendTransaction {
        repository.findAll().toFastList()
    }

    suspend fun findByIdOrNull(id: Int): UserRecord? = suspendTransaction {
        repository.findByIdOrNull(id)
    }

    suspend fun findByEmail(email: String): List<UserRecord> = suspendTransaction {
        repository.findByEmail(email).toFastList()
    }

    suspend fun addUser(user: UserRecord): UserRecord? = suspendTransaction {
        log.debug { "Save new user. $user" }
        val newId = repository.save(user)
        user.copy(id = newId.value)
    }

    suspend fun updateUser(id: Int, user: UserRecord): UserRecord? = suspendTransaction {
        val count = repository.update(user)
        if (count > 0) user else null
    }

    suspend fun deleteUser(id: Int): Boolean = suspendTransaction {
        repository.deleteById(id) > 0
    }
}
