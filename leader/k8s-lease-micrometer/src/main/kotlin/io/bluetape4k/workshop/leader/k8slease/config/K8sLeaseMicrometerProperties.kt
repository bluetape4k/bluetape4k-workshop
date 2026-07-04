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
 * Configuration properties for the Kubernetes Lease Micrometer workshop.
 *
 * ## Behavior / Contract
 * - [enabled] keeps the real Kubernetes client opt-in; default tests never require a cluster.
 * - [identity] maps to `LeaderElectionOptions.nodeId` for Kubernetes Lease audit metadata.
 * - [namespace] and [leaseName] identify the Kubernetes `coordination.k8s.io/v1` Lease.
 * - [leaseTime] must be greater than or equal to [waitTime].
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
     * Converts Spring-bound Java durations to the Kotlin durations required by `bluetape4k-leader-k8s`.
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
