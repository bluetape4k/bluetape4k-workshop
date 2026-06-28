package io.bluetape4k.workshop.leader.k8slease.config

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
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
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(identity.isNotBlank()) { "identity must not be blank" }
        require(leaseName.isNotBlank()) { "leaseName must not be blank" }
        require(!waitTime.isNegative) { "waitTime must be zero or positive. waitTime=$waitTime" }
        require(!leaseTime.isNegative && !leaseTime.isZero) { "leaseTime must be positive. leaseTime=$leaseTime" }
        require(!retryDelay.isNegative && !retryDelay.isZero) { "retryDelay must be positive. retryDelay=$retryDelay" }
        require(!jobFixedDelay.isNegative && !jobFixedDelay.isZero) {
            "jobFixedDelay must be positive. jobFixedDelay=$jobFixedDelay"
        }
        require(!simulatedWorkTime.isNegative) {
            "simulatedWorkTime must be zero or positive. simulatedWorkTime=$simulatedWorkTime"
        }
        require(leaseTime >= waitTime) {
            "leaseTime must be >= waitTime. leaseTime=$leaseTime, waitTime=$waitTime"
        }
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
