package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Describes one learner-visible capability of a leader-election backend.
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
