package io.bluetape4k.workshop.leader.k8slease.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class K8sLeaseMicrometerPropertiesTest {

    @Test
    fun `default properties are smoke safe`() {
        val properties = K8sLeaseMicrometerProperties()

        properties.enabled.shouldBeFalse()
        properties.namespace shouldBeEqualTo "workshop"
        properties.identity shouldBeEqualTo "local-workshop"
        properties.leaseName shouldBeEqualTo "workshop-nightly-export"
        properties.leaseTime shouldBeEqualTo Duration.ofSeconds(30)
        properties.waitTime shouldBeEqualTo Duration.ofSeconds(2)
    }

    @Test
    fun `valid properties map into Kubernetes lease options`() {
        val properties = K8sLeaseMicrometerProperties(
            enabled = true,
            namespace = "orders",
            identity = "orders-api-1",
            leaseName = "orders-export",
            waitTime = Duration.ofSeconds(3),
            leaseTime = Duration.ofSeconds(45),
            retryDelay = Duration.ofMillis(75),
        )

        val options = properties.toKubernetesLeaseOptions()

        properties.enabled.shouldBeTrue()
        options.namespace shouldBeEqualTo "orders"
        options.retryDelay shouldBeEqualTo kotlin.time.Duration.parse("75ms")
        options.leaderOptions.waitTime shouldBeEqualTo kotlin.time.Duration.parse("3s")
        options.leaderOptions.leaseTime shouldBeEqualTo kotlin.time.Duration.parse("45s")
        options.leaderOptions.nodeId shouldBeEqualTo "orders-api-1"
    }

    @Test
    fun `blank namespace identity and lease name are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            K8sLeaseMicrometerProperties(namespace = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            K8sLeaseMicrometerProperties(identity = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            K8sLeaseMicrometerProperties(leaseName = " ")
        }
    }

    @Test
    fun `lease time must be at least wait time`() {
        assertFailsWith<IllegalArgumentException> {
            K8sLeaseMicrometerProperties(
                waitTime = Duration.ofSeconds(30),
                leaseTime = Duration.ofSeconds(2),
            )
        }
    }
}
