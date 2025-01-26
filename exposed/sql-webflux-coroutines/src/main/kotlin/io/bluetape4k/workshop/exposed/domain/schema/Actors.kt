package io.bluetape4k.workshop.exposed.domain.schema

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.date

/**
 * 영화 배우를 저장하는 테이블
 *
 * ```sql
 * CREATE TABLE IF NOT EXISTS ACTORS (
 *      ID INT AUTO_INCREMENT PRIMARY KEY,
 *      FIRST_NAME VARCHAR(255) NOT NULL,
 *      LAST_NAME VARCHAR(255) NOT NULL,
 *      DATE_OF_BIRTH DATE NULL
 * );
 * ```
 */
object Actors: IntIdTable("actors") {
    val firstName = varchar("first_name", 255)
    val lastName = varchar("last_name", 255)
    val dateOfBirth = date("date_of_birth").nullable()
}
