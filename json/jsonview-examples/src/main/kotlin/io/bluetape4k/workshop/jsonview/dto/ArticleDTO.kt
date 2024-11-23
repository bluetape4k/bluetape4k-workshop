package io.bluetape4k.workshop.jsonview.dto

import com.fasterxml.jackson.annotation.JsonView

data class ArticleDTO(

    @JsonView(Views.Public::class)
    val id: Long?,

    @JsonView(Views.Public::class)
    val title: String?,

    @JsonView(Views.Public::class)
    val category: String?,

    val content: String?,

    @JsonView(Views.Analytics::class)
    val views: Long?,

    @JsonView(Views.Analytics::class)
    val likes: Long?,
)
