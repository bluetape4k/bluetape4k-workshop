package io.bluetape4k.workshop.exposed.domain.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table


/**
 * 영화([Movies])에 출연한 영화 배우([Actors])에 대한 정보를 저장하는 테이블 (Many-to-Many 관계를 표현합니다)
 *
 * ```sql
 * CREATE TABLE IF NOT EXISTS ACTORS_IN_MOVIES (
 *      ACTOR_ID INT,
 *      MOVIE_ID INT,
 *
 *      CONSTRAINT pk_actors_in_movies PRIMARY KEY (MOVIE_ID, ACTOR_ID),
 *      CONSTRAINT FK_ACTORS_IN_MOVIES_ACTOR_ID__ID
 *          FOREIGN KEY (ACTOR_ID) REFERENCES ACTORS(ID)
 *          ON DELETE CASCADE ON UPDATE RESTRICT,
 *      CONSTRAINT FK_ACTORS_IN_MOVIES_MOVIE_ID__ID
 *          FOREIGN KEY (MOVIE_ID) REFERENCES MOVIES(ID)
 *          ON DELETE CASCADE ON UPDATE RESTRICT
 * );
 * ```
 */
object ActorsInMovies: Table("actors_in_movies") {

    val actorId = integer("actor_id").references(Actors.id, onDelete = ReferenceOption.CASCADE)
    val movieId = integer("movie_id").references(Movies.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(movieId, actorId)
}
