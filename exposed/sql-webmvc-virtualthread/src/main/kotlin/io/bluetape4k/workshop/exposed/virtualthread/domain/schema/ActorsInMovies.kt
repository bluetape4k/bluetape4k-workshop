package io.bluetape4k.workshop.exposed.virtualthread.domain.schema

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * 영화([Movies])에 출연한 영화 배우([Actors])에 대한 정보를 저장하는 테이블 (Many-to-Many 관계를 표현합니다)
 */
object ActorsInMovies: Table("actors_in_movies") {

    val actorId = integer("actor_id").references(Actors.id, onDelete = ReferenceOption.CASCADE)
    val movieId = integer("movie_id").references(Movies.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(movieId, actorId)
}
