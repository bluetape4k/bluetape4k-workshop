package io.bluetape4k.workshop.leader.backendcomparison.observability

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityReason
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProbe
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.bluetape4k.workshop.leader.backendcomparison.service.LeaderBackendCatalog
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test

class LeaderBackendDiagnosticsProviderTest {

    private val catalog = LeaderBackendCatalog()

    @Test
    fun `descriptor follows every catalog profile`() {
        catalog.all().forEach { profile ->
            val descriptor = ProfiledLeaderElector(
                catalog = catalog,
                properties = LeaderBackendDiagnosticsProperties(backendId = profile.id),
            ).backendDescriptor

            descriptor.backendId shouldBeEqualTo profile.id
            descriptor.displayName shouldBeEqualTo profile.displayName
            descriptor.capabilities.singleExecutionModels shouldBeEqualTo NativeExecutionModels
            descriptor.capabilities.groupExecutionModels shouldBeEqualTo NativeExecutionModels
            descriptor.capabilities.leaseExtension.single shouldBeEqualTo LeaderBackendSupport.SUPPORTED
            descriptor.capabilities.leaseExtension.group shouldBeEqualTo LeaderBackendSupport.SUPPORTED

            when (profile.id) {
                "redis-lettuce" -> {
                    descriptor.capabilities.auditState.single shouldBeEqualTo LeaderBackendSupport.UNSUPPORTED
                    descriptor.capabilities.auditState.group shouldBeEqualTo LeaderBackendSupport.UNSUPPORTED
                    descriptor.capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.BACKEND
                    descriptor.capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.SERVER_TTL
                }
                "zookeeper-curator" -> {
                    descriptor.capabilities.auditState.single shouldBeEqualTo LeaderBackendSupport.UNSUPPORTED
                    descriptor.capabilities.auditState.group shouldBeEqualTo LeaderBackendSupport.UNSUPPORTED
                    descriptor.capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.NOT_APPLICABLE
                    descriptor.capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.SESSION
                }
                "kubernetes-lease" -> {
                    descriptor.capabilities.auditState.single shouldBeEqualTo LeaderBackendSupport.SUPPORTED
                    descriptor.capabilities.auditState.group shouldBeEqualTo LeaderBackendSupport.UNSUPPORTED
                    descriptor.capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.PROCESS
                    descriptor.capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.CLIENT_LEASE
                }
            }
        }
    }

    @Test
    fun `passive diagnostics returns selected profile and does not check connectivity`() {
        val provider = provider(ProbeOutcome.UP)

        val diagnostics = provider.diagnostics()

        diagnostics.descriptor.backendId shouldBeEqualTo "redis-lettuce"
        diagnostics.connectivity.status shouldBeEqualTo LeaderBackendConnectivityStatus.NOT_CHECKED
        diagnostics.connectivity.reason shouldBeEqualTo LeaderBackendConnectivityReason.NOT_CHECKED
        diagnostics.connectivity.checkedAt.shouldBeNull()
    }

    @Test
    fun `active probe maps up and down outcomes`() {
        val up = provider(ProbeOutcome.UP).checkConnectivity(250.milliseconds)
        val down = provider(ProbeOutcome.DOWN).checkConnectivity(250.milliseconds)

        up.status shouldBeEqualTo LeaderBackendConnectivityStatus.UP
        up.reason shouldBeEqualTo LeaderBackendConnectivityReason.CONNECTED
        down.status shouldBeEqualTo LeaderBackendConnectivityStatus.DOWN
        down.reason shouldBeEqualTo LeaderBackendConnectivityReason.DISCONNECTED
    }

    @Test
    fun `active probe maps unknown and unsupported outcomes`() {
        val unknown = provider(ProbeOutcome.UNKNOWN).checkConnectivity(250.milliseconds)
        val unsupported = provider(ProbeOutcome.UNSUPPORTED).checkConnectivity(250.milliseconds)

        unknown.status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
        unknown.reason shouldBeEqualTo LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED
        unsupported.status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
        unsupported.reason shouldBeEqualTo LeaderBackendConnectivityReason.PROVIDER_UNSUPPORTED
    }

    @Test
    fun `active probe normalizes provider exception`() {
        val diagnostics = provider(ProbeOutcome.EXCEPTION).checkConnectivity(250.milliseconds)

        diagnostics.status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
        diagnostics.reason shouldBeEqualTo LeaderBackendConnectivityReason.PROVIDER_EXCEPTION
    }

    @Test
    fun `cancellation is rethrown by the provider`() {
        val cancellation = CancellationException("cancelled")

        val error = assertFailsWith<CancellationException> {
            provider(ProbeOutcome.CANCELLED, cancellation).checkConnectivity(250.milliseconds)
        }

        error shouldBeEqualTo cancellation
    }

    @Test
    fun `diagnostics probe forwards a positive finite provider budget`() {
        var receivedTimeout: kotlin.time.Duration? = null

        val diagnostics = LeaderBackendDiagnosticsProbe.check(250.milliseconds) {
            receivedTimeout = it
            LeaderBackendConnectivityStatus.UP
        }

        receivedTimeout shouldBeEqualTo 250.milliseconds
        diagnostics.status shouldBeEqualTo LeaderBackendConnectivityStatus.UP
    }

    @Test
    fun `diagnostics probe rejects non-positive or infinite budgets`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendDiagnosticsProbe.check(Duration.ZERO) {
                LeaderBackendConnectivityStatus.UP
            }
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderBackendDiagnosticsProbe.check(kotlin.time.Duration.INFINITE) {
                LeaderBackendConnectivityStatus.UP
            }
        }
    }

    private fun provider(
        outcome: ProbeOutcome,
        cancellation: CancellationException = CancellationException("cancelled"),
    ): ProfiledLeaderElector = ProfiledLeaderElector(
        catalog = catalog,
        properties = LeaderBackendDiagnosticsProperties(
            backendId = "redis-lettuce",
            probeOutcome = outcome,
        ),
        cancellation = cancellation,
    )

    private companion object {
        val NativeExecutionModels = setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.SUSPEND,
        )
    }
}
