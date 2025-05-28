package io.bluetape4k.workshop.exposed.domain.schema

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.datetime.datetime
import java.io.Serializable

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

class Movie(id: EntityID<Int>): IntEntity(id), Serializable {
    companion object: IntEntityClass<Movie>(Movies)

    var name by Movies.name
    var producerName by Movies.producerName
    var releaseDate by Movies.releaseDate

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String = toStringBuilder()
        .add("name", name)
        .add("producerName", producerName)
        .add("releaseDate", releaseDate)
        .toString()
}
