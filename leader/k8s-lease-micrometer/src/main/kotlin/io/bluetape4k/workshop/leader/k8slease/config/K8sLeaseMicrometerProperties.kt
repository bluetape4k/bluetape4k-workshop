package io.bluetape4k.workshop.leader.k8slease.config

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Kubernetes Lease Micrometer 워크숍의 configuration property입니다.
 *
 * ## 동작 / 계약
 * - [enabled]는 실제 Kubernetes client를 opt-in으로 유지합니다. 기본 테스트는 cluster를 요구하지 않습니다.
 * - [identity]는 Kubernetes Lease audit metadata를 위해 `LeaderElectionOptions.nodeId`에 매핑됩니다.
 * - [namespace]와 [leaseName]은 Kubernetes `coordination.k8s.io/v1` Lease를 식별합니다.
 * - [leaseTime]은 [waitTime]보다 크거나 같아야 합니다.
 */
@ConfigurationProperties(prefix = "workshop.leader.k8s")
data class K8sLeaseMicrometerProperties(
    val enabled: Boolean = false,
    val namespace: String = "workshop",
    val identity: String = "local-workshop",
    val leaseName: String = "workshop-nightly-export",
    val waitTime: Duration = Duration.ofSeconds(2),
    val leaseTime: Duration = Duration.ofSeconds(30),
    val retryDelay: Duration = Duration.ofMillis(50),
    val jobFixedDelay: Duration = Duration.ofSeconds(10),
    val simulatedWorkTime: Duration = Duration.ofMillis(100),
    val autoExtend: Boolean = true,
) : Serializable {

    init {
        namespace.requireNotBlank("namespace")
        identity.requireNotBlank("identity")
        leaseName.requireNotBlank("leaseName")
        waitTime.requireZeroOrPositive("waitTime")
        leaseTime.requirePositive("leaseTime")
        retryDelay.requirePositive("retryDelay")
        jobFixedDelay.requirePositive("jobFixedDelay")
        simulatedWorkTime.requireZeroOrPositive("simulatedWorkTime")
        leaseTime.requireGreaterOrEqual(waitTime, "leaseTime", "waitTime")
    }

    /**
     * Spring이 바인딩한 Java duration을 `bluetape4k-leader-k8s`가 요구하는 Kotlin duration으로 변환합니다.
     */
    fun toKubernetesLeaseOptions(): KubernetesLeaseOptions =
        KubernetesLeaseOptions(
            namespace = namespace,
            retryDelay = retryDelay.toKotlinDuration(),
            leaderOptions = LeaderElectionOptions(
                waitTime = waitTime.toKotlinDuration(),
                leaseTime = leaseTime.toKotlinDuration(),
                nodeId = identity,
                autoExtend = autoExtend,
            ),
        )

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private fun Duration.requireZeroOrPositive(parameterName: String): Duration = apply {
    compareTo(Duration.ZERO).requireInRange(0, Int.MAX_VALUE, parameterName)
}

private fun Duration.requirePositive(parameterName: String): Duration = apply {
    compareTo(Duration.ZERO).requireInRange(1, Int.MAX_VALUE, parameterName)
}

private fun Duration.requireGreaterOrEqual(minimum: Duration, parameterName: String, minimumName: String): Duration = apply {
    compareTo(minimum).requireInRange(0, Int.MAX_VALUE, "$parameterName.compareTo($minimumName)")
}
