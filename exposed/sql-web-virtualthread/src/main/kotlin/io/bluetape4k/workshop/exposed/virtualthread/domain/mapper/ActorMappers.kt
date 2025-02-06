package io.bluetape4k.workshop.exposed.virtualthread.domain.mapper

import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Actors
import org.jetbrains.exposed.sql.ResultRow

fun ResultRow.toActorDTO() = ActorDTO(
    id = this[Actors.id].value,
    firstName = this[Actors.firstName],
    lastName = this[Actors.lastName],
    dateOfBirth = this[Actors.dateOfBirth].toString()
)
