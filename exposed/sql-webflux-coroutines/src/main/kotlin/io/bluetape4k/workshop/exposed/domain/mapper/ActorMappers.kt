package io.bluetape4k.workshop.exposed.domain.mapper

import io.bluetape4k.workshop.exposed.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.domain.schema.Actor
import io.bluetape4k.workshop.exposed.domain.schema.ActorTable
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toActorDTO() = ActorDTO(
    id = this[ActorTable.id].value,
    firstName = this[ActorTable.firstName],
    lastName = this[ActorTable.lastName],
    dateOfBirth = this[ActorTable.dateOfBirth].toString()
)

fun Actor.toActorDTO(): ActorDTO = ActorDTO(
    id = this.id.value,
    firstName = this.firstName,
    lastName = this.lastName,
    dateOfBirth = this.dateOfBirth?.toString()
)
