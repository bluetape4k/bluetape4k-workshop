package io.bluetape4k.workshop.exposed.domain.mapping.manytomany

import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.*

class Group(id: EntityID<UUID>): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<Group>(GroupTable)

    var name by GroupTable.name
    var description by GroupTable.description
    var owner by User referencedOn GroupTable.owner
    val members by User.via(MemberTable.group, MemberTable.user)
}
