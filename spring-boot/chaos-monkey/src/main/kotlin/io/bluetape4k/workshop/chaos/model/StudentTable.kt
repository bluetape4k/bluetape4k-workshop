package io.bluetape4k.workshop.chaos.model

import org.jetbrains.exposed.v1.core.Table

/** Chaos Monkey 학생 CRUD가 사용하는 H2 `student` 테이블 정의입니다. */
object StudentTable: Table("student") {

    val id = integer("id")
    val name = varchar("name", 255)
    val passportNumber = varchar("passport_number", 255)

    override val primaryKey = PrimaryKey(id)
}
