package io.bluetape4k.workshop.exposed.r2dbc.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserQbeResponse
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserSummary
import io.bluetape4k.workshop.exposed.r2dbc.domain.repository.UserExposedRepository
import io.bluetape4k.workshop.exposed.r2dbc.domain.repository.UserQueryByExampleRepository
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service

@Service
class UserService(
    private val database: R2dbcDatabase,
    private val repository: UserExposedRepository,
    private val queryByExampleRepository: UserQueryByExampleRepository,
) {

    companion object : KLoggingChannel()

    suspend fun findAll(): List<UserRecord> = suspendTransaction(db = database) {
        repository.findAll().toList()
    }

    suspend fun findByIdOrNull(id: Int): UserRecord? = suspendTransaction(db = database) {
        repository.findByIdOrNull(id)
    }

    suspend fun findByEmail(email: String): List<UserRecord> = suspendTransaction(db = database) {
        repository.findByEmail(email).toList()
    }

    suspend fun addUser(user: UserRecord): UserRecord? = suspendTransaction(db = database) {
        log.debug { "Save new user. $user" }
        val newId = repository.save(user)
        user.withId(newId.value)
    }

    suspend fun updateUser(id: Int, user: UserRecord): UserRecord? = suspendTransaction(db = database) {
        val count = repository.update(user.withId(id))
        if (count > 0) user else null
    }

    suspend fun deleteUser(id: Int): Boolean = suspendTransaction(db = database) {
        repository.deleteById(id) > 0
    }

    /**
     * Spring Data Exposed 2.0.0 coroutine-native QBE와 projection을 조합합니다.
     * QBE factory가 각 terminal의 transaction을 소유하므로 이 메서드는 별도 outer
     * transaction을 열지 않습니다. 반환 DTO에는 선택한 projection 필드만 포함합니다.
     */
    suspend fun queryByExample(
        loginPrefix: String?,
        email: String?,
        page: Int,
        size: Int,
    ): UserQbeResponse {
        require(page >= 0) { "page must be non-negative" }
        require(size in 1..100) { "size must be between 1 and 100" }

        val example = userExample(loginPrefix, email)
        val resultPage = queryByExampleRepository.findBy(example) { query ->
            query
                .asType(UserSummaryProjection::class)
                .project("name", "login")
                .sortBy(Sort.by(Sort.Direction.ASC, "name"))
                .page(PageRequest.of(page, size))
        }
        val items = resultPage.content.map { UserSummary(name = it.name, login = it.login) }

        return UserQbeResponse(
            items = items,
            count = queryByExampleRepository.count(example),
            exists = queryByExampleRepository.exists(example),
            page = page,
            size = size,
            hasNext = resultPage.hasNext(),
        )
    }

    private fun userExample(loginPrefix: String?, email: String?): Example<UserRecord> {
        val ignoredPaths = buildList {
            add("id")
            add("name")
            add("avatar")
            if (loginPrefix.isNullOrBlank()) add("login")
            if (email.isNullOrBlank()) add("email")
        }
        var matcher = ExampleMatcher.matching().withIgnorePaths(*ignoredPaths.toTypedArray())
        if (!loginPrefix.isNullOrBlank()) {
            matcher = matcher.withMatcher("login", ExampleMatcher.GenericPropertyMatchers.startsWith())
        }
        if (!email.isNullOrBlank()) {
            matcher = matcher.withMatcher("email", ExampleMatcher.GenericPropertyMatchers.exact())
        }

        return Example.of(
            UserRecord(
                name = "",
                login = loginPrefix.orEmpty(),
                email = email.orEmpty(),
                avatar = null,
                id = -1,
            ),
            matcher,
        )
    }

    private interface UserSummaryProjection {
        val name: String
        val login: String
    }
}
