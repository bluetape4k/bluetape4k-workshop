package io.bluetape4k.workshop.leader.k8slease.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.micrometer.LeaderMetricTagMode
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderElectionListener
import io.bluetape4k.workshop.leader.k8slease.leader.DisabledLeaderCoordinator
import io.bluetape4k.workshop.leader.k8slease.leader.LeaderCoordinator
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "workshop.leader.k8s.enabled=false",
        "workshop.leader.k8s.simulated-work-time=1ms",
    ],
)
class K8sLeaseMicrometerContextTest(
    private val coordinator: LeaderCoordinator,
    private val kubernetesClient: ObjectProvider<KubernetesClient>,
    private val observationOptions: LeaderObservationOptions,
    private val observationRecorder: MicrometerObservationLeaderAopMetricsRecorder,
    private val observationListener: MicrometerObservationLeaderElectionListener,
) {

    @Test
    fun `default context uses disabled coordinator and does not create Kubernetes client`() {
        coordinator.shouldBeInstanceOf<DisabledLeaderCoordinator>()
        kubernetesClient.ifAvailable.shouldBeNull()
    }

    @Test
    fun `default context wires observation components with hashed identity policy`() {
        observationRecorder shouldBeInstanceOf MicrometerObservationLeaderAopMetricsRecorder::class
        observationListener shouldBeInstanceOf MicrometerObservationLeaderElectionListener::class
        observationOptions.includeLockName shouldBeEqualTo true
        observationOptions.includeLeaderId shouldBeEqualTo true
        observationOptions.tagOptions.lockName.mode shouldBeEqualTo LeaderMetricTagMode.HASH
        observationOptions.tagOptions.lockName.hashLength shouldBeEqualTo 12
        observationOptions.tagOptions.leaderId.mode shouldBeEqualTo LeaderMetricTagMode.HASH
        observationOptions.tagOptions.leaderId.hashLength shouldBeEqualTo 12
    }
}
