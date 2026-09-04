package io.bluetape4k.workshop.exposed.mvc.jdbc.author.dto

import java.io.Serializable

/**
 * Book cursor endpoint의 wire response입니다.
 *
 * `nextCursor`는 이 예제에서 기본 키를 그대로 노출한 값입니다. 실제 서비스에서는
 * 호출자가 정렬·조건·테넌트 범위에 묶어 인코딩하고 서명하며 만료를 적용해야 합니다.
 */
data class BookCursorPageResponse(
    val content: List<BookDTO>,
    val nextCursor: Long?,
    val hasNext: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
