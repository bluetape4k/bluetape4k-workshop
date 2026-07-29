package io.bluetape4k.workshop.observability.basic.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.io.Serializable

/**
 * downstream inventory service 가 반환하는 단일 item 의 inventory availability 입니다.
 *
 * ## Behavior / Contract
 * - downstream API 추가를 허용하기 위해 unknown JSON field 는 무시합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Inventory(
    val itemId: Long,
    val available: Int,
) : Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
