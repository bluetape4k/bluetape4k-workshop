package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import java.io.Serializable

/**
 * 이 워크숍에서 backend를 지원하는 수준입니다.
 */
enum class BackendStatus {
    STABLE,
    PREVIEW_OPT_IN,
}

/**
 * leader-election backend 동작을 비교하는 데 사용하는 source-backed profile입니다.
 *
 * 이 profile은 문서화 목적에 맞춰 backend primitive, failover trigger, tuning surface,
 * 관찰 지점, 학습자가 실제 backend로 연습할 수 있는 기존 모듈을 요약합니다.
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
