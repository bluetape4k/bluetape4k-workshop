package io.bluetape4k.workshop.cassandra.projection

import java.io.Serializable

interface CustomerProjection: Serializable {
    val firstname: String
}
