package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderBackendCatalogTest {

    private val catalog = LeaderBackendCatalog()

    @Test
    fun `catalog contains Redis ZooKeeper and Kubernetes Lease profiles`() {
        val profiles = catalog.all()

        profiles.map { it.id } shouldBeEqualTo listOf("redis-lettuce", "zookeeper-curator", "kubernetes-lease")
        profiles.map { it.status } shouldContain BackendStatus.STABLE
        profiles.shouldNotBeEmpty()
    }

    @Test
    fun `Redis profile documents TTL based failover and event observation`() {
        val redis = catalog.findById("redis-lettuce")

        redis.failoverTrigger shouldBeEqualTo "Lease TTL expiry or explicit release"
        redis.metricsAndEvents shouldContain "LeaderElectionEvent Flow"
        redis.practiceModulePath shouldBeEqualTo "leader/leader-election"
    }

    @Test
    fun `ZooKeeper profile documents session based failover and group leadership`() {
        val zookeeper = catalog.findById("zookeeper-curator")

        zookeeper.failoverTrigger shouldBeEqualTo "ZooKeeper session loss"
        zookeeper.capabilities.map { it.label } shouldContain "Group leadership"
        zookeeper.practiceModulePath shouldBeEqualTo "leader/leader-zookeeper"
    }

    @Test
    fun `Kubernetes profile documents opt-in Lease and Micrometer path`() {
        val kubernetes = catalog.findById("kubernetes-lease")

        kubernetes.status shouldBeEqualTo BackendStatus.PREVIEW_OPT_IN
        kubernetes.metricsAndEvents shouldContain "leader-micrometer meters"
        kubernetes.practiceModulePath shouldBeEqualTo "leader/k8s-lease-micrometer"
    }

    @Test
    fun `unknown backend id fails with learner friendly message`() {
        val error = assertFailsWith<IllegalArgumentException> {
            catalog.findById("missing-backend")
        }

        error.message shouldBeEqualTo "Unknown leader backend id: missing-backend"
    }
}
