package io.bluetape4k.workshop.exposed.virtualthread.domain.schema

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date

/**
 * 영화 배우를 저장하는 테이블
 */
object Actors: Table("actors") {
    val id = integer("id").autoIncrement()
    val firstName = varchar("first_name", 255)
    val lastName = varchar("last_name", 255)
    val dateOfBirth = date("date_of_birth").nullable()

    override val primaryKey = PrimaryKey(id)
}
