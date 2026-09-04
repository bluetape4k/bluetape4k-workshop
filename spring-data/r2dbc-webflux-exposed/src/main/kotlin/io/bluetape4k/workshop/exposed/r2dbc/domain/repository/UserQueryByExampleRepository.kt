package io.bluetape4k.workshop.exposed.r2dbc.domain.repository

import io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedR2dbcQueryByExampleRepository
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.toUserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.schema.UserSchema.UserTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Spring Data coroutine-native Query by Example을 사용하는 읽기 예제 repository입니다.
 *
 * 기존 [UserExposedRepository]의 명시적 Exposed DSL CRUD와 분리하여, Spring Data factory가
 * QBE·FluentQuery·projection 구현을 생성하도록 합니다.
 */
interface UserQueryByExampleRepository : ExposedR2dbcQueryByExampleRepository<UserRecord, Int> {

    override val table: UserTable
        get() = UserTable

    override fun extractId(entity: UserRecord): Int? = entity.id.takeIf { it > 0 }

    override fun toDomain(row: ResultRow): UserRecord = row.toUserRecord()

    override fun toPersistValues(domain: UserRecord): Map<Column<*>, Any?> = mapOf(
        UserTable.name to domain.name,
        UserTable.login to domain.login,
        UserTable.email to domain.email,
        UserTable.avatar to domain.avatar,
    )
}
