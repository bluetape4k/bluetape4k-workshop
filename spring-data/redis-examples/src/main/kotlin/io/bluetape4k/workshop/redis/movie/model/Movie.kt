package io.bluetape4k.workshop.redis.movie.model

import io.bluetape4k.support.randomString
import org.springframework.data.annotation.Id
import org.springframework.data.redis.core.RedisHash
import org.springframework.data.redis.core.index.Indexed
import java.io.Serializable

@RedisHash("movies")
data class Movie(
    @Indexed
    val name: String,
    val genre: String,

    @Indexed
    val year: Int,

    @get:Id
    var hashId: String? = null,
): Serializable {

    var description: String = randomString(1024)
        private set
}
