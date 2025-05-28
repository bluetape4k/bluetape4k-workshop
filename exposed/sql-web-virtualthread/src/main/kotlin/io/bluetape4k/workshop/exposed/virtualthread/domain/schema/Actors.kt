package io.bluetape4k.workshop.exposed.virtualthread.domain.schema

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.date
import java.io.Serializable


/**
 * 영화 배우를 저장하는 테이블
 *
 * ```sql
 * CREATE TABLE IF NOT EXISTS ACTORS (
 *      ID SERIAL PRIMARY KEY,
 *      FIRST_NAME VARCHAR(255) NOT NULL,
 *      LAST_NAME VARCHAR(255) NOT NULL,
 *      DATE_OF_BIRTH DATE NULL
 * );
 * ```
 *
 */
object Actors: IntIdTable("actors") {
    val firstName = varchar("first_name", 255)
    val lastName = varchar("last_name", 255)
    val dateOfBirth = date("date_of_birth").nullable()
}


class Actor(id: EntityID<Int>): IntEntity(id), Serializable {
    companion object: IntEntityClass<Actor>(Actors)

    var firstName by Actors.firstName
    var lastName by Actors.lastName
    var dateOfBirth by Actors.dateOfBirth

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String = toStringBuilder()
        .add("firstName", firstName)
        .add("lastName", lastName)
        .add("dateOfBirth", dateOfBirth)
        .toString()
}
