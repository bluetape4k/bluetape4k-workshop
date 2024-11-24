package io.bluetape4k.workshop.exposed.virtualthread.domain.schema

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * 영화 정보를 담은 Table
 */
object Movies: Table("movies") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val producerName = varchar("producer_name", 255)
    val releaseDate = datetime("release_date")

    override val primaryKey: PrimaryKey = PrimaryKey(id)

}
