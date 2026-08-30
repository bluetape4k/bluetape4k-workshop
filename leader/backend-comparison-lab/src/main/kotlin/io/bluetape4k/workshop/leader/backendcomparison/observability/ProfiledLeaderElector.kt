package io.bluetape4k.workshop.leader.backendcomparison.observability

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityReason
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProbe
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendProfile
import io.bluetape4k.workshop.leader.backendcomparison.service.LeaderBackendCatalog
import java.util.concurrent.CancellationException
import kotlin.time.Duration

/**
 * 선택한 workshop profile의 diagnostics만 재현하는 credential-free elector입니다.
 *
 * leader 실행은 [delegate]에 위임하고, diagnostics probe는 실제 client나 네트워크를
 * 만들지 않습니다. 따라서 Spring Boot 운영 표면을 검증하면서도 기본 smoke 경계를
 * 유지할 수 있습니다.
 */
class ProfiledLeaderElector(
    catalog: LeaderBackendCatalog,
    properties: LeaderBackendDiagnosticsProperties,
    private val delegate: LeaderElector = LocalLeaderElector(),
    private val cancellation: CancellationException = CancellationException("diagnostics probe cancelled"),
) : LeaderElector by delegate, LeaderBackendDiagnosticsProvider {

    private val profile: BackendProfile = catalog.findById(properties.backendId)
    private val probeOutcome: ProbeOutcome = properties.probeOutcome

    override val backendDescriptor: LeaderBackendDescriptor = profile.toDiagnosticsDescriptor()

    /** 설정된 결과를 upstream의 bounded probe 경계로 변환합니다. */
    override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity =
        LeaderBackendDiagnosticsProbe.check(
            timeout = timeout,
            unknownReason = probeOutcome.unknownReason(),
        ) {
            when (probeOutcome) {
                ProbeOutcome.UP -> LeaderBackendConnectivityStatus.UP
                ProbeOutcome.DOWN -> LeaderBackendConnectivityStatus.DOWN
                ProbeOutcome.UNKNOWN,
                ProbeOutcome.UNSUPPORTED,
                -> LeaderBackendConnectivityStatus.UNKNOWN
                ProbeOutcome.EXCEPTION -> throw IllegalStateException("simulated provider failure")
                ProbeOutcome.CANCELLED -> throw cancellation
            }
        }

    private fun ProbeOutcome.unknownReason(): LeaderBackendConnectivityReason =
        when (this) {
            ProbeOutcome.UNSUPPORTED -> LeaderBackendConnectivityReason.PROVIDER_UNSUPPORTED
            else -> LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED
        }
}

private fun BackendProfile.toDiagnosticsDescriptor(): LeaderBackendDescriptor =
    LeaderBackendDescriptor(
        backendId = id,
        displayName = displayName,
        capabilities = when (id) {
            "redis-lettuce" -> LeaderBackendCapabilities(
                singleExecutionModels = NativeExecutionModels,
                groupExecutionModels = NativeExecutionModels,
                leaseExtension = SupportedModes,
                auditState = UnsupportedModes,
                clockSource = LeaderBackendClockSource.BACKEND,
                ttlMode = LeaderBackendTtlMode.SERVER_TTL,
            )
            "zookeeper-curator" -> LeaderBackendCapabilities(
                singleExecutionModels = NativeExecutionModels,
                groupExecutionModels = NativeExecutionModels,
                leaseExtension = SupportedModes,
                auditState = UnsupportedModes,
                clockSource = LeaderBackendClockSource.NOT_APPLICABLE,
                ttlMode = LeaderBackendTtlMode.SESSION,
            )
            "kubernetes-lease" -> LeaderBackendCapabilities(
                singleExecutionModels = NativeExecutionModels,
                groupExecutionModels = NativeExecutionModels,
                leaseExtension = SupportedModes,
                auditState = LeaderBackendModeSupport(
                    single = LeaderBackendSupport.SUPPORTED,
                    group = LeaderBackendSupport.UNSUPPORTED,
                ),
                clockSource = LeaderBackendClockSource.PROCESS,
                ttlMode = LeaderBackendTtlMode.CLIENT_LEASE,
            )
            else -> error("Unsupported workshop leader profile: $id")
        },
    )

private val NativeExecutionModels: Set<LeaderExecutionModel> = setOf(
    LeaderExecutionModel.BLOCKING,
    LeaderExecutionModel.ASYNC,
    LeaderExecutionModel.SUSPEND,
)

private val SupportedModes = LeaderBackendModeSupport(
    single = LeaderBackendSupport.SUPPORTED,
    group = LeaderBackendSupport.SUPPORTED,
)

private val UnsupportedModes = LeaderBackendModeSupport(
    single = LeaderBackendSupport.UNSUPPORTED,
    group = LeaderBackendSupport.UNSUPPORTED,
)
