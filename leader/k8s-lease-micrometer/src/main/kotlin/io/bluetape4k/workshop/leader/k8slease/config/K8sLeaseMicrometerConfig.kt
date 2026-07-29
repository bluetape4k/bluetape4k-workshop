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
 * Kubernetes Lease Micrometer 워크숍의 Spring wiring입니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(K8sLeaseMicrometerProperties::class)
class K8sLeaseMicrometerConfig {

    /**
     * 명시적으로 활성화한 경우에만 실제 Kubernetes client를 만듭니다.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "workshop.leader.k8s", name = ["enabled"], havingValue = "true")
    fun kubernetesClient(): KubernetesClient =
        KubernetesClientBuilder().build()

    /**
     * 실제 Kubernetes Lease coordinator를 만들고 upstream Micrometer decorator metric으로 감쌉니다.
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
     * local test와 smoke run에서 사용하는 기본 coordinator입니다.
     */
    @Bean
    @ConditionalOnMissingBean(LeaderCoordinator::class)
    fun disabledLeaderCoordinator(): LeaderCoordinator =
        DisabledLeaderCoordinator()

    @Bean
    fun k8sLeaseMetrics(registry: MeterRegistry): K8sLeaseMetrics =
        K8sLeaseMetrics(registry)
}
