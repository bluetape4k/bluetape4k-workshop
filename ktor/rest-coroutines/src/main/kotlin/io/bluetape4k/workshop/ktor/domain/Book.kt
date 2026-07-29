package io.bluetape4k.workshop.ktor.domain

import kotlinx.serialization.Serializable

/**
 * catalog 의 book 을 표현합니다.
 *
 * ## Behavior / Contract
 * - `@Serializable` (kotlinx) 은 ContentNegotiation 과 SSE encoding 에서 compile-time JSON serialization 을 가능하게 합니다. 이는 NDJSON export 에 사용하는 Jackson 3 serialization 과 독립적입니다.
 * - 모든 data class 에 적용하는 workspace convention 에 따라 `java.io.Serializable` 을 구현합니다.
 * - database annotation 이 없는 pure value object 입니다.
 *
 * ```kotlin
 * val book = Book(id = "b-1", title = "Kotlin in Action", author = "Jemerov", year = 2017)
 * ```
 */
@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val year: Int,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
