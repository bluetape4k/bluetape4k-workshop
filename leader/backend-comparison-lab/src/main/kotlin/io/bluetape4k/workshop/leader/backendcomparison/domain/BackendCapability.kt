package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * 학습자가 확인할 수 있는 leader-election backend 기능 하나를 설명합니다.
 */
data class BackendCapability(
    val label: String,
    val detail: String,
) : Serializable {

    init {
        label.requireNotBlank("label")
        detail.requireNotBlank("detail")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
