package io.bluetape4k.workshop.exposed.domain.schema

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * 영화 정보를 담은 Table
 *
 * ```sql
 * CREATE TABLE IF NOT EXISTS MOVIES (
 *      ID INT AUTO_INCREMENT PRIMARY KEY,
 *      "name" VARCHAR(255) NOT NULL,
 *      PRODUCER_NAME VARCHAR(255) NOT NULL,
 *      RELEASE_DATE DATETIME(9) NOT NULL
 * );
 * ```
 */
object Movies: IntIdTable("movies") {
    val name = varchar("name", 255)
    val producerName = varchar("producer_name", 255)
    val releaseDate = datetime("release_date")
}
