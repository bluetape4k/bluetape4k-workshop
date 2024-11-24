package io.bluetape4k.workshop.exposed.domain.model.manytomany

import io.bluetape4k.workshop.exposed.domain.schema.manytomany.GroupTable
import io.bluetape4k.workshop.exposed.domain.schema.manytomany.MemberTable
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.*

class Group(id: EntityID<UUID>): UUIDEntity(id) {
    companion object: UUIDEntityClass<Group>(GroupTable)

    var name by GroupTable.name
    var description by GroupTable.description
    var owner by User referencedOn GroupTable.owner
    val members by User via MemberTable
}
