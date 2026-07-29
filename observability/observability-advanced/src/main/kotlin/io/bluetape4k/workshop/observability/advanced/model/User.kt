package io.bluetape4k.workshop.observability.advanced.model

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * database 에 저장되는 user entity 를 표현합니다.
 *
 * ## Behavior / Contract
 * - Redis cache serialization 을 위해 [Serializable] 을 구현합니다.
 */
data class User(
    val id: Long,
    val name: String,
    val email: String,
) : Serializable {
    init {
        id.requirePositiveNumber("id")
        name.requireNotBlank("name")
        email.requireNotBlank("email")
    }

    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
