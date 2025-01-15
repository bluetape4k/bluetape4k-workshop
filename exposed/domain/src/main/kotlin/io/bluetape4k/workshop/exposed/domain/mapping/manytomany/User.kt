package io.bluetape4k.workshop.exposed.domain.mapping.manytomany

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.*

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
