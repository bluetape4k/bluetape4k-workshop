package io.bluetape4k.workshop.exposed.domain.schema

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * 영화 정보를 담은 Table
 */
object Movies: IntIdTable("movies") {
    val name = varchar("name", 255)
    val producerName = varchar("producer_name", 255)
    val releaseDate = datetime("release_date")
}
