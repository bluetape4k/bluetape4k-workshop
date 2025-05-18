package io.bluetape4k.workshop.micrometer.model

import java.io.Serializable

data class Todo(
    val userId: Int,
    val id: Int,
    val title: String,
    val completed: Boolean,
): Serializable
