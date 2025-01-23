package io.bluetape4k.workshop.exposed.domain.mapping.manytomany

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable
import io.bluetape4k.workshop.exposed.domain.mapping.manytomany.MemberSchema.UserStatus.UNKNOWN
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.*

object MemberSchema {

    val memberTables = arrayOf(GroupTable, MemberTable, UserTable)

    /**
     * Group Table
     *
     * @see MemberTable
     * @see UserTable
     * @see Group
     */
    object GroupTable: TimebasedUUIDTable() {
        val name = varchar("name", 50)
        val description = text("description")
        val createAt = datetime("created_at").defaultExpression(CurrentDateTime)

        val owner = reference("owner_id", UserTable)
    }


    /**
     * Member Table (UserTable, GroupTable의 Many-to-Many 관계를 나타내는 테이블)
     *
     * @see UserTable
     * @see GroupTable
     */
    object MemberTable: TimebasedUUIDTable() {

        val user = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
        val group = reference("group_id", GroupTable, onDelete = ReferenceOption.CASCADE)

        val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    }

    /**
     * User Table
     *
     * @see User
     * @see GroupTable
     * @see MemberTable
     */
    object UserTable: TimebasedUUIDTable() {
        val firstName = varchar("first_name", 50)
        val lastName = varchar("last_name", 50)
        val username = varchar("username", 50)
        val status = enumeration<UserStatus>("status").default(UNKNOWN)
        val createAt = datetime("created_at").defaultExpression(CurrentDateTime)
    }

    class Group(id: EntityID<UUID>): TimebasedUUIDEntity(id) {
        companion object: TimebasedUUIDEntityClass<Group>(GroupTable)

        var name by GroupTable.name
        var description by GroupTable.description
        var owner by User referencedOn GroupTable.owner
        val members by User.via(MemberTable.group, MemberTable.user)
    }


    class User(id: EntityID<UUID>): TimebasedUUIDEntity(id) {

        companion object: TimebasedUUIDEntityClass<User>(UserTable)

        var firstName by UserTable.firstName
        var lastName by UserTable.lastName
        var username by UserTable.username
        var status by UserTable.status
        val groups by Group.via(MemberTable.user, MemberTable.group)


        override fun toString(): String = ToStringBuilder(this)
            .add("id", id)
            .add("username", username)
            .add("status", status)
            .toString()
    }

    enum class UserStatus {
        UNKNOWN,
        ACTIVE,
        INACTIVE,
        BANNED;
    }

}
