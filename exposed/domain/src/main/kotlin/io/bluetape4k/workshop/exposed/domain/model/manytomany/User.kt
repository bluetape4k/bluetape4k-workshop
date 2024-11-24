package io.bluetape4k.workshop.exposed.domain.model.manytomany

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.workshop.exposed.domain.schema.manytomany.MemberTable
import io.bluetape4k.workshop.exposed.domain.schema.manytomany.UserTable
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.*

class User(id: EntityID<UUID>): UUIDEntity(id) {

    companion object: UUIDEntityClass<User>(UserTable)

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
