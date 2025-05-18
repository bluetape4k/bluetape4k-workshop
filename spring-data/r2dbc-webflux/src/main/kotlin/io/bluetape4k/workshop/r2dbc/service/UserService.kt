package io.bluetape4k.workshop.r2dbc.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.r2dbc.domain.User
import io.bluetape4k.workshop.r2dbc.domain.UserDTO
import io.bluetape4k.workshop.r2dbc.domain.toModel
import io.bluetape4k.workshop.r2dbc.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class UserService(private val repository: UserRepository) {

    companion object: KLoggingChannel()

    fun findAll(): Flow<User> = repository.findAll()

    suspend fun findById(id: Int): User? = repository.findById(id)

    fun findByEmail(email: String): Flow<User> = repository.findByEmail(email)

    @Transactional
    suspend fun addUser(user: UserDTO): User? {
        log.debug { "Save new user. ${user.toModel()}" }
        return repository.save(user.toModel())
    }

    @Transactional
    suspend fun updateUser(id: Int, user: UserDTO): User? {
        return when {
            repository.existsById(id) -> repository.save(user.toModel(withId = id))
            else -> null
        }
    }

    @Transactional
    suspend fun deleteUser(id: Int): Boolean {
        if (repository.existsById(id)) {
            repository.deleteById(id)
            return true
        }
        return false
    }
}
