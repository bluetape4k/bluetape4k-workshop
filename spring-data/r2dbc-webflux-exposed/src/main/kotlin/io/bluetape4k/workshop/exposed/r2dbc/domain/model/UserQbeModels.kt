package io.bluetape4k.workshop.exposed.r2dbc.domain.model

import java.io.Serializable

/** QBE projection에서 선택한 사용자 공개 필드만 담는 응답입니다. */
data class UserSummary(
    val name: String,
    val login: String,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Query by Example 결과와 count/exists/page 메타데이터를 함께 반환합니다.
 * [items]에는 `name`, `login` projection만 포함하여 email/avatar를 SQL로 읽지 않습니다.
 */
data class UserQbeResponse(
    val items: List<UserSummary>,
    val count: Long,
    val exists: Boolean,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
