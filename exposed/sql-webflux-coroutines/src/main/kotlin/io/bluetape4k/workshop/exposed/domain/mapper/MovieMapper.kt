package io.bluetape4k.workshop.exposed.domain.mapper

import io.bluetape4k.workshop.exposed.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieDTO
import io.bluetape4k.workshop.exposed.domain.dto.MovieWithActorDTO
import io.bluetape4k.workshop.exposed.domain.schema.Movie
import io.bluetape4k.workshop.exposed.domain.schema.Movies
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toMovieDTO(): MovieDTO = MovieDTO(
    id = this[Movies.id].value,
    name = this[Movies.name],
    producerName = this[Movies.producerName],
    releaseDate = this[Movies.releaseDate].toString(),
)

fun ResultRow.toMovieWithActorsDTO(actors: List<ActorDTO>): MovieWithActorDTO = MovieWithActorDTO(
    id = this[Movies.id].value,
    name = this[Movies.name],
    producerName = this[Movies.producerName],
    releaseDate = this[Movies.releaseDate].toString(),
    actors = actors.toMutableList()
)

fun Movie.toMovieDTO(): MovieDTO = MovieDTO(
    id = this.id.value,
    name = this.name,
    producerName = this.producerName,
    releaseDate = this.releaseDate.toString(),
)
