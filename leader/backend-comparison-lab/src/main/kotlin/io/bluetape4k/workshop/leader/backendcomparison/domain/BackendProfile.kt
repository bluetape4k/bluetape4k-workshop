package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import java.io.Serializable

/**
 * Support level for a backend in this workshop.
 */
enum class BackendStatus {
    STABLE,
    PREVIEW_OPT_IN,
}

/**
 * Source-backed profile used to compare leader-election backend behavior.
 *
 * The profile is documentation-oriented: it summarizes the backend primitive,
 * failover trigger, tuning surface, observation points, and the existing module
 * where learners can practice against a real backend.
 */
data class BackendProfile(
    val id: String,
    val displayName: String,
    val status: BackendStatus,
    val primitive: String,
    val failoverTrigger: String,
    val tuningKnob: String,
    val metricsAndEvents: List<String>,
    val bestFor: String,
    val avoidWhen: String,
    val practiceModulePath: String,
    val capabilities: List<BackendCapability>,
) : Serializable {

    init {
        id.requireNotBlank("id")
        displayName.requireNotBlank("displayName")
        primitive.requireNotBlank("primitive")
        failoverTrigger.requireNotBlank("failoverTrigger")
        tuningKnob.requireNotBlank("tuningKnob")
        metricsAndEvents.requireNotEmpty("metricsAndEvents")
        bestFor.requireNotBlank("bestFor")
        avoidWhen.requireNotBlank("avoidWhen")
        practiceModulePath.requireNotBlank("practiceModulePath")
        capabilities.requireNotEmpty("capabilities")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
