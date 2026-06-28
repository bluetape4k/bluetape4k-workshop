package io.bluetape4k.workshop.leader.k8slease.config

import io.bluetape4k.leader.k8s.KubernetesLeaseSuspendLeaderElector
import io.bluetape4k.leader.micrometer.InstrumentedSuspendLeaderElector
import io.bluetape4k.workshop.leader.k8slease.leader.DisabledLeaderCoordinator
import io.bluetape4k.workshop.leader.k8slease.leader.ElectorLeaderCoordinator
import io.bluetape4k.workshop.leader.k8slease.leader.LeaderCoordinator
import io.bluetape4k.workshop.leader.k8slease.metrics.K8sLeaseMetrics
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Spring wiring for the Kubernetes Lease Micrometer workshop.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(K8sLeaseMicrometerProperties::class)
class K8sLeaseMicrometerConfig {

    /**
     * Creates the real Kubernetes client only when explicitly enabled.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "workshop.leader.k8s", name = ["enabled"], havingValue = "true")
    fun kubernetesClient(): KubernetesClient =
        KubernetesClientBuilder().build()

    /**
     * Creates the real Kubernetes Lease coordinator and wraps it with upstream Micrometer decorator metrics.
     */
    @Bean
    @ConditionalOnProperty(prefix = "workshop.leader.k8s", name = ["enabled"], havingValue = "true")
    fun kubernetesLeaderCoordinator(
        client: KubernetesClient,
        properties: K8sLeaseMicrometerProperties,
        registry: MeterRegistry,
    ): LeaderCoordinator {
        val elector = KubernetesLeaseSuspendLeaderElector(client, properties.toKubernetesLeaseOptions())
        val instrumented = InstrumentedSuspendLeaderElector(elector, registry, properties.leaseName)
        return ElectorLeaderCoordinator(instrumented)
    }

    /**
     * Default coordinator used by local tests and smoke runs.
     */
    @Bean
    @ConditionalOnMissingBean(LeaderCoordinator::class)
    fun disabledLeaderCoordinator(): LeaderCoordinator =
        DisabledLeaderCoordinator()

    @Bean
    fun k8sLeaseMetrics(registry: MeterRegistry): K8sLeaseMetrics =
        K8sLeaseMetrics(registry)
}
