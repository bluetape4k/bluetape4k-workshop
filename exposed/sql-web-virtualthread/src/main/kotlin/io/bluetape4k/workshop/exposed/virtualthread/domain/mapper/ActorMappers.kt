package io.bluetape4k.workshop.exposed.virtualthread.domain.mapper

import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Actor
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Actors
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toActorDTO() = ActorDTO(
    id = this[Actors.id].value,
    firstName = this[Actors.firstName],
    lastName = this[Actors.lastName],
    dateOfBirth = this[Actors.dateOfBirth].toString()
)

fun Actor.toActorDTO() = ActorDTO(
    id = this.id.value,
    firstName = this.firstName,
    lastName = this.lastName,
    dateOfBirth = this.dateOfBirth?.toString()
)
