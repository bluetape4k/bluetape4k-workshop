package io.bluetape4k.workshop.exposed.domain.schema

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.date

/**
 * 영화 배우를 저장하는 테이블
 */
object Actors: IntIdTable("actors") {
    val firstName = varchar("first_name", 255)
    val lastName = varchar("last_name", 255)
    val dateOfBirth = date("date_of_birth").nullable()
}
