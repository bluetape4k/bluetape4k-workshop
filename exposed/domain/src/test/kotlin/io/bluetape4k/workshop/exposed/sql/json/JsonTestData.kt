package io.bluetape4k.workshop.exposed.sql.json

import io.bluetape4k.workshop.exposed.dao.idValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.json.json
import org.jetbrains.exposed.sql.json.jsonb

object JsonTestData {

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS j_table (
     *      id SERIAL PRIMARY KEY,
     *      j_column JSON NOT NULL
     * )
     * ```
     */
    object JsonTable: IntIdTable("j_table") {
        val jsonColumn = json<DataHolder>("j_column", Json.Default)
    }

    object JsonBTable: IntIdTable("j_b_table") {
        val jsonBColumn = jsonb<DataHolder>("j_b_column", Json.Default)
    }

    class JsonEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<JsonEntity>(JsonTable)

        var jsonColumn: DataHolder by JsonTable.jsonColumn

        override fun equals(other: Any?): Boolean = other is JsonEntity && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "JsonEntity(id=$idValue, jsonColumn=$jsonColumn)"
    }

    class JsonBEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<JsonBEntity>(JsonBTable)

        var jsonBColumn by JsonBTable.jsonBColumn

        override fun equals(other: Any?): Boolean = other is JsonBEntity && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "JsonBEntity(id=$idValue, jsonBColumn=$jsonBColumn)"
    }

    /**
     * ```sql
     * -- Postgres
     * CREATE TABLE IF NOT EXISTS j_arrays (
     *      id SERIAL PRIMARY KEY,
     *      "groups" JSON NOT NULL,
     *      numbers JSON NOT NULL
     * );
     * ```
     */
    object JsonArrays: IntIdTable("j_arrays") {
        val groups = json<UserGroup>("groups", Json.Default)
        val numbers = json<IntArray>("numbers", Json.Default)
    }

    object JsonBArrays: IntIdTable("j_b_arrays") {
        val groups = jsonb<UserGroup>("groups", Json.Default)
        val numbers = jsonb<IntArray>("numbers", Json.Default)
    }
}

@Serializable
data class DataHolder(
    val user: User,
    val logins: Int,
    val active: Boolean,
    val team: String?,
)

@Serializable
data class UserGroup(
    val users: List<User>,
)

@Serializable
data class User(
    val name: String,
    val team: String?,
)
