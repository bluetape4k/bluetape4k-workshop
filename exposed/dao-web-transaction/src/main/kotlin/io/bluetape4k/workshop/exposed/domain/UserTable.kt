package io.bluetape4k.workshop.exposed.domain

import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * User 정보를 나타내는 Table
 *
 * ```sql
 * CREATE TABLE IF NOT EXISTS "User" (
 *      ID BIGSERIAL PRIMARY KEY,
 *      "name" VARCHAR(50) NOT NULL,
 *      AGE INT NOT NULL
 * )
 * ```
 */
object UserTable: LongIdTable() {
    val name = varchar("name", length = 50)
    val age = integer("age")
}
