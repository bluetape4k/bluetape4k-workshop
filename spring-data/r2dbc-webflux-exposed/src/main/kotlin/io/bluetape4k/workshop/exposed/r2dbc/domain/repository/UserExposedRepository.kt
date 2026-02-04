package io.bluetape4k.workshop.exposed.r2dbc.domain.repository

import io.bluetape4k.exposed.r2dbc.repository.ExposedR2dbcRepository
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.toUserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.schema.UserSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.upsert
import org.springframework.stereotype.Repository

@Repository
class UserExposedRepository: ExposedR2dbcRepository<UserRecord, Int> {

    override val table: UserSchema.UserTable = UserSchema.UserTable

    override suspend fun ResultRow.toEntity(): UserRecord = toUserRecord()

    suspend fun save(user: UserRecord): EntityID<Int> {
        return table.insertAndGetId {
            it[table.name] = user.name
            it[table.login] = user.login
            it[table.email] = user.email
            it[table.avatar] = user.avatar
        }
    }

    suspend fun update(user: UserRecord): Int {
        return table.update(where = { table.id eq user.id }) {
            it[table.name] = user.name
            it[table.login] = user.login
            it[table.email] = user.email
            it[table.avatar] = user.avatar
        }
    }

    suspend fun upsert(user: UserRecord) {
        table.upsert(where = { table.id eq user.id }) {
            it[table.name] = user.name
            it[table.login] = user.login
            it[table.email] = user.email
            it[table.avatar] = user.avatar
        }
    }

    suspend fun findByEmail(email: String): Flow<UserRecord> {
        return table.selectAll()
            .where { table.email eq email }
            .map { it.toEntity() }
    }
}
