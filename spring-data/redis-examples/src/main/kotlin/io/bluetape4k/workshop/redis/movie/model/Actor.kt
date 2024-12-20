package io.bluetape4k.workshop.redis.movie.model

import io.bluetape4k.support.randomString
import org.springframework.data.annotation.Id
import org.springframework.data.redis.core.RedisHash
import org.springframework.data.redis.core.index.Indexed
import java.io.Serializable

@RedisHash("actors")
data class Actor(
    @Indexed val firstname: String,
    @Indexed val lastname: String,
    @get:Id
    var hashId: String? = null,
): Serializable {

    var description: String = randomString(1024)
        private set

}
