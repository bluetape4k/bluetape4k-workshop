package io.bluetape4k.workshop.exposed.domain.mapping.entities

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.date


object TaskTable: LongIdTable("tasks") {
    val status = enumerationByName("status", 10, TaskStatusType::class)
    val changedOn = date("changed_on")
    val changedBy = varchar("changed_by", 255)
}

class TaskEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<TaskEntity>(TaskTable)

    var status by TaskTable.status
    var changedOn by TaskTable.changedOn
    var changedBy by TaskTable.changedBy

    override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
    override fun equals(other: Any?): Boolean = other is TaskEntity && other.id == this.id
    override fun toString(): String {
        return "TaskEntity(id=$id, status=$status, changedOn=$changedOn, changedBy='$changedBy')"
    }
}

enum class TaskStatusType {
    TO_DO,
    DONE,
    FAILED
}
