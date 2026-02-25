package io.bluetape4k.workshop.exposed.domain.mapping.associations.manytomany

import io.bluetape4k.exposed.dao.entityToStringBuilder
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable
import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytomany.MemberSchema.UserStatus.UNKNOWN
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.javatime.CurrentDateTime
import org.jetbrains.exposed.v1.javatime.datetime
import java.util.*

object MemberSchema {

    val memberTables = arrayOf(GroupTable, MemberTable, UserTable)

    /**
     * Group Table
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS "Group" (
     *      id uuid PRIMARY KEY,
     *      "name" VARCHAR(50) NOT NULL,
     *      description TEXT NOT NULL,
     *      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
     *      owner_id uuid NOT NULL,
     *
     *      CONSTRAINT fk_group_owner_id__id FOREIGN KEY (owner_id)
     *      REFERENCES "User"(id) ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
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
     * ```sql
     * CREATE TABLE IF NOT EXISTS "Member" (
     *      id uuid PRIMARY KEY,
     *      user_id uuid NOT NULL,
     *      group_id uuid NOT NULL,
     *      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
     *
     *      CONSTRAINT fk_member_user_id__id FOREIGN KEY (user_id)
     *      REFERENCES "User"(id) ON DELETE CASCADE ON UPDATE RESTRICT,
     *
     *      CONSTRAINT fk_member_group_id__id FOREIGN KEY (group_id)
     *      REFERENCES "Group"(id) ON DELETE CASCADE ON UPDATE RESTRICT
     * );
     * ```
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
     * ```sql
     * CREATE TABLE IF NOT EXISTS "User" (
     *      id uuid PRIMARY KEY,
     *      first_name VARCHAR(50) NOT NULL,
     *      last_name VARCHAR(50) NOT NULL,
     *      username VARCHAR(50) NOT NULL,
     *      status INT DEFAULT 0 NOT NULL,
     *      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
     * );
     * ```
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

        val createdAt by GroupTable.createAt

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = entityToStringBuilder()
            .add("name", name)
            .add("description", description)
            .add("createdAt", createdAt)
            .toString()
    }


    class User(id: EntityID<UUID>): TimebasedUUIDEntity(id) {

        companion object: TimebasedUUIDEntityClass<User>(UserTable)

        var firstName by UserTable.firstName
        var lastName by UserTable.lastName
        var username by UserTable.username
        var status by UserTable.status
        val groups by Group.via(MemberTable.user, MemberTable.group)

        val createdAt by UserTable.createAt


        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = entityToStringBuilder()
            .add("firstName", firstName)
            .add("lastName", lastName)
            .add("username", username)
            .add("status", status)
            .add("createdAt", createdAt)
            .toString()
    }

    enum class UserStatus {
        UNKNOWN,
        ACTIVE,
        INACTIVE,
        BANNED;
    }
}
