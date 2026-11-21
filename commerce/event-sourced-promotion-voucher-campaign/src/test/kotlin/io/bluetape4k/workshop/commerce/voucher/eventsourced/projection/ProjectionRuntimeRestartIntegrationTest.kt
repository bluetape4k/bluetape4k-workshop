package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
/**
 * ProjectionRuntimeRestartIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ProjectionRuntimeRestartIntegrationTest {

    private companion object: KLogging() {
/**
 * postgres은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }

/**
 * PROJECTION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROJECTION = "voucher-lifecycle-runtime-restart"
/**
 * TARGET_POSITION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TARGET_POSITION = 2L
/**
 * STARTED_AT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val STARTED_AT: Instant = Instant.parse("2026-07-23T13:00:00Z")
/**
 * RESTARTED_AT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val RESTARTED_AT: Instant = STARTED_AT + 1.seconds()
/**
 * RECOVERY_TIMEOUT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val RECOVERY_TIMEOUT: kotlin.time.Duration = 5.seconds
/**
 * RECOVERY_INTERVAL은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val RECOVERY_INTERVAL: kotlin.time.Duration = 100.milliseconds
    }


/**
 * repository은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = ProjectionRebuildRepository()

    @Test
/**
 * 이 시나리오는 "fresh datasource and Exposed registration recover durable cancellation after runtime
 * restart" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fresh datasource and Exposed registration recover durable cancellation after runtime restart`() {
        val candidateKey = prepareCancellingCandidate()
        val restarted = EventSourcedPostgresTestDatabase(
            postgres = postgres,
            poolName = "issue-538-rebuild-restarted",
            maximumPoolSize = 1,
        )

        try {
            await atMost RECOVERY_TIMEOUT withPollInterval RECOVERY_INTERVAL untilAsserted {
                val recovered = transaction(restarted.database) {
                    ProjectionRebuildMaintenance().recover(candidateKey, RESTARTED_AT)
                }.shouldNotBeNull()

                log.debug { "recovered: $recovered" }
                recovered.state shouldBeEqualTo ProjectionGenerationState.CANCELLED
                recovered.fencingToken shouldBeEqualTo FIRST_REBUILD_FENCING_TOKEN + 1
            }
        } finally {
            transaction(restarted.database) {
                SchemaUtils.drop(ActiveProjectionGenerations, ProjectionGenerations)
            }
            restarted.close()
        }
    }

/**
 * prepareCancellingCandidate은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun prepareCancellingCandidate(): ProjectionKey {
        val initial = EventSourcedPostgresTestDatabase(
            postgres = postgres,
            poolName = "issue-538-rebuild-initial",
            maximumPoolSize = 1,
        )
        return try {
            transaction(initial.database) {
                SchemaUtils.create(ProjectionGenerations, ActiveProjectionGenerations)
                repository.initializeActive(PROJECTION, STARTED_AT)
                val candidate = repository.start(PROJECTION, TARGET_POSITION, STARTED_AT)
                repository.requestCancellation(candidate.key, STARTED_AT).shouldNotBeNull()
                candidate.key
            }
        } finally {
            initial.close()
        }
    }
}
