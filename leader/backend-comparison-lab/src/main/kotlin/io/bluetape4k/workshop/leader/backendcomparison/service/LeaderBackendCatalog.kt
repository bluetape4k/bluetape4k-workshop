package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendCapability
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendProfile
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendStatus
import org.springframework.stereotype.Service

/**
 * Provides source-backed comparison profiles for supported leader backends.
 */
@Service
class LeaderBackendCatalog {

    private val profiles: List<BackendProfile> = listOf(
        BackendProfile(
            id = "redis-lettuce",
            displayName = "Redis + Lettuce",
            status = BackendStatus.STABLE,
            primitive = "Redis key with lease TTL",
            failoverTrigger = "Lease TTL expiry or explicit release",
            tuningKnob = "waitTime, leaseTime, autoExtend",
            metricsAndEvents = listOf(
                "LeaderElectionEvent Flow",
                "listener callbacks",
                "skip/elected/revoked events",
            ),
            bestFor = "Services that already operate Redis and need fast, simple scheduled-job leadership.",
            avoidWhen = "Redis outage should not affect scheduler ownership or a session-bound lock is required.",
            practiceModulePath = "leader/leader-election",
            capabilities = listOf(
                BackendCapability("Blocking and coroutine APIs", "LettuceLeaderElector plus LettuceSuspendLeaderElector."),
                BackendCapability("TTL recovery", "Leadership can recover after lease expiry when an owner disappears."),
                BackendCapability("Event observation", "ListeningLeaderElector exposes callbacks and Flow events."),
            ),
        ),
        BackendProfile(
            id = "zookeeper-curator",
            displayName = "ZooKeeper + Curator",
            status = BackendStatus.STABLE,
            primitive = "Ephemeral znode / Curator mutex",
            failoverTrigger = "ZooKeeper session loss",
            tuningKnob = "sessionTimeoutMs, connectionTimeoutMs, groupMaxLeaders",
            metricsAndEvents = listOf(
                "single-leader result",
                "group-leader slot result",
                "Curator connection state",
            ),
            bestFor = "Services already depending on ZooKeeper or needing session-bound ownership.",
            avoidWhen = "A Redis-style lock TTL or auto-extension behavior is expected.",
            practiceModulePath = "leader/leader-zookeeper",
            capabilities = listOf(
                BackendCapability("Single leadership", "One candidate owns the lock path and executes."),
                BackendCapability("Group leadership", "Up to groupMaxLeaders candidates may enter."),
                BackendCapability("Session recovery", "Ownership follows ZooKeeper session lifecycle, not a TTL field."),
            ),
        ),
        BackendProfile(
            id = "kubernetes-lease",
            displayName = "Kubernetes Lease",
            status = BackendStatus.PREVIEW_OPT_IN,
            primitive = "coordination.k8s.io/v1 Lease object",
            failoverTrigger = "Lease expiry and resource-version update",
            tuningKnob = "namespace, identity, leaseTime, retryDelay, autoExtend",
            metricsAndEvents = listOf(
                "leader-micrometer meters",
                "workshop.k8s.lease.* meters",
                "Prometheus scrape",
            ),
            bestFor = "Kubernetes-native workloads that can grant Lease RBAC and expose Micrometer metrics.",
            avoidWhen = "Local tests must exercise a real backend without a cluster or service-account setup.",
            practiceModulePath = "leader/k8s-lease-micrometer",
            capabilities = listOf(
                BackendCapability("Opt-in real backend", "Default workshop path stays disabled without Kubernetes credentials."),
                BackendCapability("Micrometer visibility", "Application meters and leader-micrometer decorator meters are documented."),
                BackendCapability("Kubernetes ownership", "Lease records namespace and identity for operational inspection."),
            ),
        ),
    )

    private val profilesById: Map<String, BackendProfile> =
        profiles.associateBy { it.id }.also { byId ->
            byId.size.requireInRange(profiles.size, profiles.size, "profilesById.size")
        }

    /**
     * Returns the profiles in the learner-facing comparison order.
     */
    fun all(): List<BackendProfile> = profiles

    /**
     * Finds a backend profile by its stable workshop identifier.
     */
    fun findById(id: String): BackendProfile {
        id.requireNotBlank("id")
        return profilesById[id]
            ?: throw IllegalArgumentException("Unknown leader backend id: $id")
    }
}
