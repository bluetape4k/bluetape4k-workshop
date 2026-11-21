@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.javatimes.days
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.requireZeroOrPositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAuditTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAllocationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBackupInventoryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCampaignTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCodeDedupTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCommandTombstoneTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolDepthTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolEntryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolHttpIdempotencyTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolLegalHoldTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolQuarantineTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReconciliationInboxTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReservationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRevokePreviewGrantTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolUserLimitTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolWorkerClaimTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import java.io.Serializable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

/**
 * BackupKeyManifest은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class BackupKeyManifest(
    /**
     * `kekVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val kekVersions: Set<String>,
    /**
     * `verificationVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val verificationVersions: Set<String>,
    /**
     * `stableDedupVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val stableDedupVersion: String,
    /**
     * `commandTombstoneVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val commandTombstoneVersion: String,
    /**
     * `userIdentityVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val userIdentityVersions: Set<String> = emptySet(),
    /**
     * `auditVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val auditVersions: Set<String> = emptySet(),
    /**
     * `signatureVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val signatureVersions: Set<String> = emptySet(),
): Serializable {

    companion object: KLogging() {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }

    init {
        require(kekVersions.isNotEmpty() && kekVersions.none(String::isBlank)) { "KEK versions must not be empty" }
        require(verificationVersions.isNotEmpty() && verificationVersions.none(String::isBlank)) {
            "verification versions must not be empty"
        }
        require(userIdentityVersions.none(String::isBlank)) { "user identity versions must not be blank" }
        require(auditVersions.none(String::isBlank)) { "audit versions must not be blank" }
        require(signatureVersions.none(String::isBlank)) { "signature versions must not be blank" }
        require(stableDedupVersion.isNotBlank()) { "stable dedup version must not be blank" }
        require(commandTombstoneVersion.isNotBlank()) { "command tombstone version must not be blank" }
    }

}

/**
 * VoucherPoolAvailableKeys은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherPoolAvailableKeys(
    /**
     * `kekVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val kekVersions: Set<String>,
    /**
     * `verificationVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val verificationVersions: Set<String>,
    /**
     * `stableDedupVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val stableDedupVersions: Set<String>,
    /**
     * `commandTombstoneVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val commandTombstoneVersions: Set<String>,
    /**
     * `userIdentityVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val userIdentityVersions: Set<String> = emptySet(),
    /**
     * `auditVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val auditVersions: Set<String> = emptySet(),
    /**
     * `signatureVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val signatureVersions: Set<String> = emptySet(),
): Serializable {
    /**
     * missing은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param manifest 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun missing(manifest: BackupKeyManifest): Set<String> = buildSet {
        addAll(manifest.kekVersions - kekVersions)
        addAll(manifest.verificationVersions - verificationVersions)
        addAll(manifest.userIdentityVersions - userIdentityVersions)
        addAll(manifest.auditVersions - auditVersions)
        addAll(manifest.signatureVersions - signatureVersions)
        if (manifest.stableDedupVersion !in stableDedupVersions) add(manifest.stableDedupVersion)
        if (manifest.commandTombstoneVersion !in commandTombstoneVersions) add(manifest.commandTombstoneVersion)
    }

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** 제공된 manifest와 독립적으로 backup metadata와 content에서 추출한 key reference입니다. */
/** 제공된 manifest와 독립적으로 backup metadata와 content에서 추출한 key reference입니다. */
internal data class VoucherPoolBackupKeyInventory(
    /**
     * `kekVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val kekVersions: Set<String>,
    /**
     * `verificationVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val verificationVersions: Set<String>,
    /**
     * `stableDedupVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val stableDedupVersions: Set<String>,
    /**
     * `commandTombstoneVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val commandTombstoneVersions: Set<String>,
    /**
     * `userIdentityVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val userIdentityVersions: Set<String>,
    /**
     * `auditVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val auditVersions: Set<String>,
    /**
     * `signatureVersions`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val signatureVersions: Set<String>,
): Serializable {
    init {
        require(
            listOf(
                kekVersions,
                verificationVersions,
                stableDedupVersions,
                commandTombstoneVersions,
                userIdentityVersions,
                auditVersions,
                signatureVersions,
            ).all { versions -> versions.isNotEmpty() && versions.none(String::isBlank) },
        ) { "backup key inventory categories must be complete" }
    }

    /**
     * missingFrom은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param manifest 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun missingFrom(manifest: BackupKeyManifest): Set<String> = buildSet {
        addAll(kekVersions - manifest.kekVersions)
        addAll(verificationVersions - manifest.verificationVersions)
        addAll(userIdentityVersions - manifest.userIdentityVersions)
        addAll(auditVersions - manifest.auditVersions)
        addAll(signatureVersions - manifest.signatureVersions)
        addAll(stableDedupVersions - setOf(manifest.stableDedupVersion))
        addAll(commandTombstoneVersions - setOf(manifest.commandTombstoneVersion))
    }

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * VoucherPoolRestoreSmokeResult은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherPoolRestoreSmokeResult(
    /**
     * `ciphertextReadableOrQuarantined`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val ciphertextReadableOrQuarantined: Boolean,
    /**
     * `countersConsistent`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val countersConsistent: Boolean,
    /**
     * `replayFenceRetained`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val replayFenceRetained: Boolean,
    /**
     * `cursorRestarted`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursorRestarted: Boolean,
    /**
     * `staleWorkerTakenOver`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val staleWorkerTakenOver: Boolean,
    /**
     * `exactlyOnceRevealRetained`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val exactlyOnceRevealRetained: Boolean,
): Serializable {
    /**
     * `passed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val passed: Boolean
        get() = ciphertextReadableOrQuarantined && countersConsistent && replayFenceRetained && cursorRestarted &&
                staleWorkerTakenOver && exactlyOnceRevealRetained

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * VoucherPoolRestoreFailureCode은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class VoucherPoolRestoreFailureCode {
    /**
     * `VoucherPoolRestoreFailureCode.INCOMPLETE_MANIFEST`은 VoucherPoolRestoreFailureCode 업무 상태의
     * `INCOMPLETE_MANIFEST` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    INCOMPLETE_MANIFEST,
    /**
     * `VoucherPoolRestoreFailureCode.MISSING_KEY`은 VoucherPoolRestoreFailureCode 업무 상태의
     * `MISSING_KEY`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    MISSING_KEY,
    /**
     * `VoucherPoolRestoreFailureCode.SMOKE_FAILED`은 VoucherPoolRestoreFailureCode 업무 상태의
     * `SMOKE_FAILED`
     * 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    SMOKE_FAILED,
}

/**
 * VoucherPoolRestoreException은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherPoolRestoreException(val code: VoucherPoolRestoreFailureCode):
    IllegalStateException("VOUCHER_POOL_RESTORE_${code.name}")

/** import 전에 모든 manifest key를 검증하고, complete smoke contract가 통과한 뒤에만 restore를 수락합니다. */
/** import 전에 모든 manifest key를 검증하고, complete smoke contract가 통과한 뒤에만 restore를 수락합니다. */
internal class VoucherPoolRestoreCoordinator(
    /**
     * `availableKeys`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val availableKeys: VoucherPoolAvailableKeys,
) {

    companion object: KLogging()

    /**
     * restore은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun restore(
        manifest: BackupKeyManifest,
        inventory: VoucherPoolBackupKeyInventory,
        importer: () -> Unit,
        smoke: () -> VoucherPoolRestoreSmokeResult,
    ): VoucherPoolRestoreSmokeResult {
        requirePreflight(manifest, inventory)
        importer()
        val result = smoke()
        if (!result.passed) throw VoucherPoolRestoreException(VoucherPoolRestoreFailureCode.SMOKE_FAILED)

        return result
    }

    /**
     * requirePreflight은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param manifest 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param inventory 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun requirePreflight(manifest: BackupKeyManifest, inventory: VoucherPoolBackupKeyInventory) {
        if (inventory.missingFrom(manifest).isNotEmpty()) {
            throw VoucherPoolRestoreException(VoucherPoolRestoreFailureCode.INCOMPLETE_MANIFEST)
        }
        if (availableKeys.missing(manifest).isNotEmpty()) {
            throw VoucherPoolRestoreException(VoucherPoolRestoreFailureCode.MISSING_KEY)
        }
    }
}

/**
 * VoucherPoolReferencedKeys은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherPoolReferencedKeys(
    /**
     * `liveRows`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val liveRows: Set<String>,
    /**
     * `commandTombstones`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val commandTombstones: Set<String>,
    /**
     * `stableDedup`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val stableDedup: Set<String>,
    /**
     * `audits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val audits: Set<String>,
    /**
     * `backupInventory`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val backupInventory: Set<String>,
    /**
     * `restoreRehearsalPassed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val restoreRehearsalPassed: Boolean,
): Serializable {
    /**
     * `all`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val all: Set<String> get() = liveRows + commandTombstones + stableDedup + audits + backupInventory

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * VoucherPoolKeyReferenceSource은 사전 생성 바우처 풀 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface VoucherPoolKeyReferenceSource {
    /**
     * referencedKeys은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun referencedKeys(): VoucherPoolReferencedKeys
}

/** retained 또는 unrehearsed backup inventory를 포함해 persisted key reference를 모두 읽습니다. */
/** retained 또는 unrehearsed backup inventory를 포함해 persisted key reference를 모두 읽습니다. */
@Suppress("SqlResolve")
internal class PostgresVoucherPoolKeyReferenceSource(
    /**
     * `dataSource`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
): VoucherPoolKeyReferenceSource {

    private companion object: KLogging() {
        /**
         * BackupReferences은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
         * 규칙에 맞춰 해석합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        class BackupReferences(val keys: Set<String>, val allRehearsed: Boolean)
        /**
         * BackupReferenceResult은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
         * 타입의 규칙에 맞춰
         * 해석합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        class BackupReferenceResult(val keys: Set<String>, val count: Long, val rehearsedCount: Long)

        val BACKUP_INVENTORY_SQL =
            """SELECT kek_versions,verification_versions,user_identity_versions,audit_versions,signature_versions,
                       stable_dedup_version,command_tombstone_version,
                       restore_rehearsed_at IS NOT NULL AS rehearsed,
                       retained_until>statement_timestamp() OR restore_rehearsed_at IS NULL AS blocking
                FROM voucher_pool_backup_inventory""".trimIndent()

    }

    /**
     * referencedKeys은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun referencedKeys(): VoucherPoolReferencedKeys =
        withExposedTransaction { connection ->
            val backup = readBackupInventory(connection)
            VoucherPoolReferencedKeys(
                liveRows = buildSet {
                    addAll(queryStrings(VoucherPoolEntryTable.kekVersion) { VoucherPoolEntryTable.select(VoucherPoolEntryTable.kekVersion).where { VoucherPoolEntryTable.kekVersion.isNotNull() } })
                    addAll(queryNullableInts(VoucherPoolEntryTable.verificationKeyVersion) { VoucherPoolEntryTable.select(VoucherPoolEntryTable.verificationKeyVersion).where { VoucherPoolEntryTable.verificationKeyVersion.isNotNull() } })
                    addAll(queryInts(VoucherPoolCampaignTable.userIdentityKeyVersion) { VoucherPoolCampaignTable.select(VoucherPoolCampaignTable.userIdentityKeyVersion) })
                    addAll(queryInts(VoucherPoolRevokePreviewGrantTable.signatureKeyVersion) { VoucherPoolRevokePreviewGrantTable.select(VoucherPoolRevokePreviewGrantTable.signatureKeyVersion) })
                },
                commandTombstones = queryInts(VoucherPoolCommandTombstoneTable.keyVersion) { VoucherPoolCommandTombstoneTable.select(VoucherPoolCommandTombstoneTable.keyVersion) },
                stableDedup = queryInts(VoucherPoolCodeDedupTable.keyVersion) { VoucherPoolCodeDedupTable.select(VoucherPoolCodeDedupTable.keyVersion) },
                audits = queryNullableInts(VoucherPoolAuditTable.auditKeyVersion) { VoucherPoolAuditTable.select(VoucherPoolAuditTable.auditKeyVersion).where { VoucherPoolAuditTable.auditKeyVersion.isNotNull() } },
                backupInventory = backup.keys,
                restoreRehearsalPassed = backup.allRehearsed,
            )
        }

    /** PostgreSQL array materialization과 statement-clock hold를 함께 읽어야 하므로 raw로 유지합니다. */
    /** PostgreSQL array materialization과 statement-clock hold를 함께 읽어야 하므로 raw로 유지합니다. */
    private fun readBackupInventory(connection: Connection): BackupReferences {
        val result = connection.createStatement().use { statement ->
            statement.executeQuery(BACKUP_INVENTORY_SQL).use(::collectBackupReferences)
        }
        return BackupReferences(
            keys = result.keys,
            allRehearsed = result.count > 0L && result.count == result.rehearsedCount
        )
    }

    /**
     * collectBackupReferences은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param result 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun collectBackupReferences(result: ResultSet): BackupReferenceResult {
        val backup = mutableSetOf<String>()
        var backupCount = 0L
        var rehearsedCount = 0L

        while (result.next()) {
            backupCount++
            if (result.getBoolean("rehearsed")) rehearsedCount++
            if (result.getBoolean("blocking")) {
                backup += result.getArray("kek_versions").array.asStringSet()
                backup += result.getArray("verification_versions").array.asStringSet()
                backup += result.getArray("user_identity_versions").array.asStringSet()
                backup += result.getArray("audit_versions").array.asStringSet()
                backup += result.getArray("signature_versions").array.asStringSet()
                backup += result.getString("stable_dedup_version")
                backup += result.getString("command_tombstone_version")
            }
        }
        return BackupReferenceResult(backup, backupCount, rehearsedCount)
    }

    /**
     * queryStrings은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param column 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param query 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun queryStrings(column: Column<String?>, query: () -> Query): Set<String> =
        query().withDistinct().orderBy(column to SortOrder.ASC).mapNotNull { it[column] }.toSet()

    /**
     * queryInts은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param column 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param query 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun queryInts(column: Column<Int>, query: () -> Query): Set<String> =
        query().withDistinct().orderBy(column to SortOrder.ASC).map { it[column].toString() }.toSet()

    /**
     * queryNullableInts은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param column 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param query 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun queryNullableInts(column: Column<Int?>, query: () -> Query): Set<String> =
        query().withDistinct().orderBy(column to SortOrder.ASC).mapNotNull { it[column]?.toString() }.toSet()

/**
 * withExposedTransaction은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private inline fun <T> withExposedTransaction(crossinline block: (Connection) -> T): T {
        val database = Database.connect(dataSource)
        return try {
            transaction(database) {
                block(TransactionManager.current().connection.connection as Connection)
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

/**
 * asStringSet은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun Any.asStringSet(): Set<String> = when (this) {
        is Array<*> -> filterIsInstance<String>().toSet()
        else        -> emptySet()
    }
}

/**
 * VoucherPoolKeyRetirementPolicy은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherPoolKeyRetirementPolicy(private val references: VoucherPoolKeyReferenceSource) {
    /**
     * canRetire은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param version 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun canRetire(version: String): Boolean {
        version.requireNotBlank("version")
        val current = references.referencedKeys()

        return current.restoreRehearsalPassed && (version !in current.all)
    }
}

/**
 * VoucherPoolRollbackStrategy은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class VoucherPoolRollbackStrategy {
    /**
     * `VoucherPoolRollbackStrategy.COMPATIBLE_PREVIOUS_BINARY`은 VoucherPoolRollbackStrategy 업무 상태의
     * `COMPATIBLE_PREVIOUS_BINARY` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    COMPATIBLE_PREVIOUS_BINARY,
    /**
     * `VoucherPoolRollbackStrategy.VERIFIED_RESTORE_AND_ROLL_FORWARD`은 VoucherPoolRollbackStrategy
     * 업무 상태의
     * `VERIFIED_RESTORE_AND_ROLL_FORWARD` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    VERIFIED_RESTORE_AND_ROLL_FORWARD,
}

/**
 * VoucherPoolRollbackPolicy은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal object VoucherPoolRollbackPolicy {
    /**
     * select은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun select(
        previousBinaryCompatible: Boolean,
        verifiedBackup: Boolean,
        restoreRehearsalPassed: Boolean,
    ): VoucherPoolRollbackStrategy = when {
        previousBinaryCompatible                 -> VoucherPoolRollbackStrategy.COMPATIBLE_PREVIOUS_BINARY
        verifiedBackup && restoreRehearsalPassed -> VoucherPoolRollbackStrategy.VERIFIED_RESTORE_AND_ROLL_FORWARD
        else                                     -> error("rollback requires a compatible previous binary or verified backup manifest")
    }
}

/**
 * RetentionKind은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class RetentionKind {
    /**
     * `RetentionKind.DESCRIPTOR`은 RetentionKind 업무 상태의 `DESCRIPTOR` 단계입니다. 허용된 상태 전이와 재시도·보상·응답
     * 분기를 선택하는
     * 기준으로 사용합니다.
     */
    DESCRIPTOR,
    /**
     * `RetentionKind.TERMINAL_INBOX_CLAIM`은 RetentionKind 업무 상태의 `TERMINAL_INBOX_CLAIM` 단계입니다. 허용된
     * 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    TERMINAL_INBOX_CLAIM,
    /**
     * `RetentionKind.TERMINAL_ENTRY_RESERVATION`은 RetentionKind 업무 상태의
     * `TERMINAL_ENTRY_RESERVATION` 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    TERMINAL_ENTRY_RESERVATION,
    /**
     * `RetentionKind.AUDIT`은 RetentionKind 업무 상태의 `AUDIT` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    AUDIT,
}

/**
 * RetentionStage은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class RetentionStage {
    /**
     * `RetentionStage.DESCRIPTORS`은 RetentionStage 업무 상태의 `DESCRIPTORS` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를
     * 선택하는 기준으로 사용합니다.
     */
    DESCRIPTORS,
    /**
     * `RetentionStage.TERMINAL_INBOX_AND_CLAIMS`은 RetentionStage 업무 상태의
     * `TERMINAL_INBOX_AND_CLAIMS` 단계입니다.
     * 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    TERMINAL_INBOX_AND_CLAIMS,
    /**
     * `RetentionStage.TERMINAL_RESERVATIONS_AND_ENTRIES`은 RetentionStage 업무 상태의
     * `TERMINAL_RESERVATIONS_AND_ENTRIES` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    TERMINAL_RESERVATIONS_AND_ENTRIES,
    /**
     * `RetentionStage.AUDIT`은 RetentionStage 업무 상태의 `AUDIT` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    AUDIT,
}

/**
 * VoucherPoolRetentionPolicy은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherPoolRetentionPolicy(
    /**
     * `descriptor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val descriptor: Duration = 24.hours(),
    /**
     * `terminalInboxAndClaim`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val terminalInboxAndClaim: Duration = 7.days(),
    /**
     * `terminalEntryAndReservation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val terminalEntryAndReservation: Duration = 30.days(),
    /**
     * `audit`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val audit: Duration = 90.days(),
): Serializable {
    init {
        descriptor.requirePositiveDuration("descriptor")
        terminalInboxAndClaim.requirePositiveDuration("terminalInboxAndClaim")
        terminalEntryAndReservation.requirePositiveDuration("terminalEntryAndReservation")
        audit.requirePositiveDuration("audit")
    }

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * VoucherPoolRetentionReport은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherPoolRetentionReport(
    /**
     * `deletedByStage`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val deletedByStage: Map<RetentionStage, Int>,
): Serializable {

    /**
     * `stages`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val stages: List<RetentionStage> get() = RetentionStage.entries

    /**
     * count은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param stage 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun count(stage: RetentionStage): Int = deletedByStage[stage] ?: 0

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * VoucherPoolRetentionBacklogMetric은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을
 * 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherPoolRetentionBacklogMetric(
    /**
     * `count`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val count: Long,
    /**
     * `oldestAge`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val oldestAge: Duration,
): Serializable {
    init {
        count.requireZeroOrPositiveNumber("count")
        oldestAge.requireZeroOrPositiveDuration("oldestAge")
    }

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * VoucherPoolRetentionBacklog은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherPoolRetentionBacklog(
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: Map<RetentionKind, VoucherPoolRetentionBacklogMetric>,
): Serializable {
    /**
     * get은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param kind 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    operator fun get(kind: RetentionKind): VoucherPoolRetentionBacklogMetric =
        metrics.getValue(kind)

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** voucher pool authority row를 위한 PostgreSQL-time, dependency-ordered retention입니다. */
/** voucher pool authority row를 위한 PostgreSQL-time, dependency-ordered retention입니다. */
internal class VoucherPoolRetention(
    /**
     * `dataSource`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
    /**
     * `policy`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val policy: VoucherPoolRetentionPolicy = VoucherPoolRetentionPolicy(),
) {
    /**
     * PostgreSQL `statement_timestamp()`와 interval projection을 한 snapshot에 고정해
     * bounded-cardinality due-row count와 oldest overdue age를 반환합니다.
     * Exposed DSL에는 이 statement-clock/interval projection을 같은 SQL 경계로 표현하는
     * portable API가 없어 backlog read-model은 raw SQL로 유지합니다.
     */
    fun backlog(): VoucherPoolRetentionBacklog =
        dataSource.connection.use { connection ->
            VoucherPoolRetentionBacklog(
                linkedMapOf(
                    RetentionKind.DESCRIPTOR to queryBacklog(
                        connection,
                        DESCRIPTOR_BACKLOG_SQL,
                        policy.descriptor,
                        policy.descriptor
                    ),
                    RetentionKind.TERMINAL_INBOX_CLAIM to queryBacklog(
                        connection,
                        INBOX_CLAIM_BACKLOG_SQL,
                        policy.terminalInboxAndClaim,
                        policy.terminalInboxAndClaim,
                    ),
                    RetentionKind.TERMINAL_ENTRY_RESERVATION to queryBacklog(
                        connection,
                        ENTRY_RESERVATION_BACKLOG_SQL,
                        policy.terminalEntryAndReservation,
                        policy.terminalEntryAndReservation,
                    ),
                    RetentionKind.AUDIT to queryBacklog(
                        connection,
                        AUDIT_BACKLOG_SQL,
                        policy.audit,
                        policy.audit
                    ),
                ),
            )
        }

    /**
     * purge은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun purge(limit: Int): VoucherPoolRetentionReport {
        limit.requireInRange(1, MAX_PURGE_LIMIT, "limit")

        return transaction { connection ->
            val descriptors = purgeDescriptors(connection, limit)
            val inboxAndClaims = purgeTerminalInbox(connection, limit) + purgeTerminalClaims(connection, limit)
            val reservationsAndEntries = purgeTerminalAllocations(connection, limit) +
                    purgeTerminalReservations(connection, limit) +
                    purgeTerminalEntries(connection, limit)
            val audits = purgeAudits(connection, limit)

            VoucherPoolRetentionReport(
                linkedMapOf(
                    RetentionStage.DESCRIPTORS to descriptors,
                    RetentionStage.TERMINAL_INBOX_AND_CLAIMS to inboxAndClaims,
                    RetentionStage.TERMINAL_RESERVATIONS_AND_ENTRIES to reservationsAndEntries,
                    RetentionStage.AUDIT to audits,
                ),
            ).also {
                log.info {
                    "voucher_pool_retention_purged descriptors=$descriptors inboxClaims=$inboxAndClaims " +
                            "terminalRows=$reservationsAndEntries audits=$audits"
                }
            }
        }
    }

    /** 모든 backup retention과 rehearsal hold가 release된 뒤에만 tenant-lifetime fence를 삭제합니다. */
    /** 모든 backup retention과 rehearsal hold가 release된 뒤에만 tenant-lifetime fence를 삭제합니다. */
    fun deleteTenant(tenantId: String): Boolean {
        tenantId.requireNotBlank("tenantId")

        return transaction { connection ->
            if (isTenantHeld(tenantId) || !isTenantBackupDeletionReady(tenantId)) {
                log.warn { "voucher_pool_tenant_delete_held" }
                return@transaction false
            }
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL voucher_pool.retention_purge = 'on'")
                statement.execute("SET CONSTRAINTS ALL DEFERRED")
            }
            VoucherPoolQuarantineTable.deleteWhere { VoucherPoolQuarantineTable.tenantId eq tenantId }
            VoucherPoolRevokePreviewGrantTable.deleteWhere { VoucherPoolRevokePreviewGrantTable.tenantId eq tenantId }
            VoucherPoolReconciliationInboxTable.deleteWhere { VoucherPoolReconciliationInboxTable.tenantId eq tenantId }
            VoucherPoolWorkerClaimTable.deleteWhere { VoucherPoolWorkerClaimTable.tenantId eq tenantId }
            VoucherPoolDepthTable.deleteWhere { VoucherPoolDepthTable.tenantId eq tenantId }
            VoucherPoolAllocationTable.deleteWhere { VoucherPoolAllocationTable.tenantId eq tenantId }
            VoucherPoolReservationTable.deleteWhere { VoucherPoolReservationTable.tenantId eq tenantId }
            VoucherPoolUserLimitTable.deleteWhere { VoucherPoolUserLimitTable.tenantId eq tenantId }
            VoucherPoolAuditTable.deleteWhere { VoucherPoolAuditTable.tenantId eq tenantId }
            VoucherPoolEntryTable.deleteWhere { VoucherPoolEntryTable.tenantId eq tenantId }
            VoucherPoolBatchTable.deleteWhere { VoucherPoolBatchTable.tenantId eq tenantId }
            VoucherPoolCampaignTable.deleteWhere { VoucherPoolCampaignTable.tenantId eq tenantId }
            VoucherPoolHttpIdempotencyTable.deleteWhere { VoucherPoolHttpIdempotencyTable.tenantId eq tenantId }
            VoucherPoolCommandTombstoneTable.deleteWhere { VoucherPoolCommandTombstoneTable.tenantId eq tenantId }
            VoucherPoolCodeDedupTable.deleteWhere { VoucherPoolCodeDedupTable.tenantId eq tenantId }
            VoucherPoolLegalHoldTable.deleteWhere { VoucherPoolLegalHoldTable.tenantId eq tenantId }
            VoucherPoolBackupInventoryTable.deleteWhere { VoucherPoolBackupInventoryTable.tenantId eq tenantId }
            log.info { "voucher_pool_tenant_deleted" }
            true
        }
    }

    /**
     * purgeDescriptors은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun purgeDescriptors(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT i.ctid FROM voucher_pool_http_idempotency i
                    JOIN voucher_pool_command_tombstones t
                      ON t.tenant_id=i.tenant_id AND t.operation=i.operation
                     AND t.scoped_key_digest=i.scoped_key_digest AND t.fingerprint=i.fingerprint
                    WHERE i.status='COMPLETED' AND i.descriptor IS NOT NULL
                      AND i.completed_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("i.tenant_id")}
                    ORDER BY i.completed_at,i.tenant_id,i.operation
                    LIMIT ? FOR UPDATE OF i SKIP LOCKED)
                UPDATE voucher_pool_http_idempotency i SET descriptor=NULL,revision=revision+1
                FROM candidates WHERE i.ctid=candidates.ctid""".trimIndent(),
        ).executeRetention(policy.descriptor, limit)

    /**
     * purgeTerminalInbox은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun purgeTerminalInbox(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT i.ctid FROM voucher_pool_reconciliation_inbox i
                    WHERE i.status IN ('APPLIED','IGNORED','FAILED_TERMINAL')
                      AND i.claim_owner IS NULL
                      AND i.next_attempt_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("i.tenant_id")}
                    ORDER BY i.next_attempt_at,i.tenant_id,i.event_id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_reconciliation_inbox i USING candidates WHERE i.ctid=candidates.ctid""".trimIndent(),
        ).executeRetention(policy.terminalInboxAndClaim, limit)

    /**
     * purgeTerminalClaims은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun purgeTerminalClaims(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT w.ctid FROM voucher_pool_worker_claims w
                    WHERE w.owner_id IS NULL AND w.checkpoint>0
                      AND w.next_attempt_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("w.tenant_id")}
                    ORDER BY w.next_attempt_at,w.tenant_id,w.scope_id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_worker_claims w USING candidates WHERE w.ctid=candidates.ctid""".trimIndent(),
        ).executeRetention(policy.terminalInboxAndClaim, limit)

    /**
     * purgeTerminalAllocations은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun purgeTerminalAllocations(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT a.ctid FROM voucher_pool_allocations a
                    JOIN voucher_pool_entries e ON e.tenant_id=a.tenant_id AND e.entry_id=a.entry_id
                    WHERE e.state IN ('REDEEMED','RELEASED','REVOKED','EXPIRED')
                      AND e.updated_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("e.tenant_id")}
                    ORDER BY e.updated_at,a.tenant_id,a.allocation_id
                    LIMIT ? FOR UPDATE OF a SKIP LOCKED)
                DELETE FROM voucher_pool_allocations a USING candidates WHERE a.ctid=candidates.ctid""".trimIndent(),
        ).executeRetention(policy.terminalEntryAndReservation, limit)

    /**
     * purgeTerminalReservations은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun purgeTerminalReservations(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT r.ctid FROM voucher_pool_reservations r
                    WHERE r.state IN ('EXPIRED','RELEASED','REVOKED')
                      AND r.reservation_expires_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT EXISTS (SELECT 1 FROM voucher_pool_allocations a
                                      WHERE a.tenant_id=r.tenant_id AND a.reservation_id=r.reservation_id)
                      AND NOT ${heldTenantSql("r.tenant_id")}
                    ORDER BY r.reservation_expires_at,r.tenant_id,r.reservation_id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_reservations r USING candidates WHERE r.ctid=candidates.ctid""".trimIndent(),
        ).executeRetention(policy.terminalEntryAndReservation, limit)

    /**
     * purgeTerminalEntries은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun purgeTerminalEntries(connection: Connection, limit: Int): Int =
        connection.prepareStatement(
            """WITH candidates AS (
                    SELECT e.ctid FROM voucher_pool_entries e
                    WHERE e.state IN ('REDEEMED','RELEASED','REVOKED','EXPIRED')
                      AND e.updated_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT EXISTS (SELECT 1 FROM voucher_pool_reservations r
                                      WHERE r.tenant_id=e.tenant_id AND r.entry_id=e.entry_id)
                      AND NOT EXISTS (SELECT 1 FROM voucher_pool_allocations a
                                      WHERE a.tenant_id=e.tenant_id AND a.entry_id=e.entry_id)
                      AND NOT ${heldTenantSql("e.tenant_id")}
                    ORDER BY e.updated_at,e.tenant_id,e.entry_id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_entries e USING candidates WHERE e.ctid=candidates.ctid""".trimIndent(),
        ).executeRetention(policy.terminalEntryAndReservation, limit)

    /**
     * purgeAudits은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param connection 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param limit 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun purgeAudits(connection: Connection, limit: Int): Int {
        connection.createStatement().use {
            it.execute("SET LOCAL voucher_pool.retention_purge = 'on'")
        }
        return connection.prepareStatement(
            """WITH candidates AS (
                    SELECT a.ctid FROM voucher_pool_audits a
                    WHERE a.created_at<=statement_timestamp()-(? * interval '1 millisecond')
                      AND NOT ${heldTenantSql("a.tenant_id")}
                    ORDER BY a.created_at,a.id
                    LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM voucher_pool_audits a USING candidates WHERE a.ctid=candidates.ctid""".trimIndent(),
        ).executeRetention(policy.audit, limit)
    }

    /**
     * isTenantHeld은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun isTenantHeld(tenantId: String): Boolean {
        val legalHold = VoucherPoolLegalHoldTable
            .select(VoucherPoolLegalHoldTable.holdId)
            .where {
                (VoucherPoolLegalHoldTable.tenantId eq tenantId) and
                        VoucherPoolLegalHoldTable.releasedAt.isNull() and
                        (VoucherPoolLegalHoldTable.holdUntil.isNull() or
                                (VoucherPoolLegalHoldTable.holdUntil greater CurrentTimestamp))
            }
            .limit(1)
            .singleOrNull() != null
        if (legalHold) return true

        return VoucherPoolQuarantineTable
            .select(VoucherPoolQuarantineTable.entryId)
            .where {
                (VoucherPoolQuarantineTable.tenantId eq tenantId) and
                        VoucherPoolQuarantineTable.resolvedAt.isNull()
            }
            .limit(1)
            .singleOrNull() != null
    }

    /**
     * isTenantBackupDeletionReady은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을
     * 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun isTenantBackupDeletionReady(tenantId: String): Boolean {
        val hasBackup = VoucherPoolBackupInventoryTable
            .select(VoucherPoolBackupInventoryTable.backupId)
            .where { VoucherPoolBackupInventoryTable.tenantId eq tenantId }
            .limit(1)
            .singleOrNull() != null
        if (!hasBackup) return false

        return VoucherPoolBackupInventoryTable
            .select(VoucherPoolBackupInventoryTable.backupId)
            .where {
                (VoucherPoolBackupInventoryTable.tenantId eq tenantId) and
                        ((VoucherPoolBackupInventoryTable.retainedUntil greater CurrentTimestamp) or
                                VoucherPoolBackupInventoryTable.restoreRehearsedAt.isNull())
            }
            .limit(1)
            .singleOrNull() == null
    }

    /**
     * heldTenantSql은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param alias 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun heldTenantSql(alias: String): String =
        """EXISTS (SELECT 1 FROM voucher_pool_legal_holds h
                     WHERE h.tenant_id=$alias AND h.released_at IS NULL
                       AND (h.hold_until IS NULL OR h.hold_until>statement_timestamp())
                   UNION ALL
                   SELECT 1 FROM voucher_pool_quarantines q
                     WHERE q.tenant_id=$alias AND q.resolved_at IS NULL
                   UNION ALL
                   SELECT 1 WHERE NOT EXISTS (
                     SELECT 1 FROM voucher_pool_backup_inventory b
                     WHERE b.tenant_id=$alias)
                   UNION ALL
                   SELECT 1 FROM voucher_pool_backup_inventory b
                     WHERE b.tenant_id=$alias AND b.restore_rehearsed_at IS NULL)""".trimIndent()

/**
 * executeRetention은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun PreparedStatement.executeRetention(duration: Duration, limit: Int): Int =
        use { statement ->
            statement.setLong(1, duration.toMillis())
            statement.setInt(2, limit)
            statement.executeUpdate()
        }

    /**
     * queryBacklog은 사전 생성 바우처 풀 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun queryBacklog(
        connection: Connection,
        sql: String,
        vararg durations: Duration,
    ): VoucherPoolRetentionBacklogMetric =
        connection.prepareStatement(sql).use { statement ->
            durations.forEachIndexed { index, duration ->
                statement.setLong(index + 1, duration.toMillis())
            }
            statement.executeQuery().use { result ->
                check(result.next())
                VoucherPoolRetentionBacklogMetric(
                    count = result.getLong("due_count"),
                    oldestAge = result.getLong("oldest_millis").millis(),
                )
            }
        }

/**
 * transaction은 사전 생성 바우처 풀 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private inline fun <T> transaction(crossinline block: (Connection) -> T): T {
        val database = Database.connect(dataSource)
        return try {
            org.jetbrains.exposed.v1.jdbc.transactions.transaction(database) {
                val connection = TransactionManager.current().connection.connection as Connection
                runCatching { block(connection) }.getOrElse { failure ->
                    log.warn(failure) { "voucher_pool_retention_transaction_failed" }
                    throw failure
                }
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
    }

    companion object: KLogging() {
        /**
         * `MAX_PURGE_LIMIT`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private const val MAX_PURGE_LIMIT = 1_000
        /**
         * `DESCRIPTOR_BACKLOG_SQL`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val DESCRIPTOR_BACKLOG_SQL =
            """SELECT count(*) AS due_count,
                       COALESCE(max(EXTRACT(EPOCH FROM
                           (statement_timestamp()-(completed_at+(? * interval '1 millisecond'))))*1000),0)::bigint
                           AS oldest_millis
                FROM voucher_pool_http_idempotency
                WHERE status='COMPLETED' AND descriptor IS NOT NULL
                  AND completed_at+(? * interval '1 millisecond')<=statement_timestamp()""".trimIndent()
        /**
         * `INBOX_CLAIM_BACKLOG_SQL`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val INBOX_CLAIM_BACKLOG_SQL =
            """SELECT count(*) AS due_count,
                       COALESCE(max(EXTRACT(EPOCH FROM (statement_timestamp()-eligible_at))*1000),0)::bigint AS oldest_millis
                FROM (
                    SELECT next_attempt_at+(? * interval '1 millisecond') AS eligible_at
                    FROM voucher_pool_reconciliation_inbox
                    WHERE status IN ('APPLIED','IGNORED','FAILED_TERMINAL') AND claim_owner IS NULL
                    UNION ALL
                    SELECT next_attempt_at+(? * interval '1 millisecond') AS eligible_at
                    FROM voucher_pool_worker_claims WHERE owner_id IS NULL AND checkpoint>0
                ) due WHERE eligible_at<=statement_timestamp()""".trimIndent()
        /**
         * `ENTRY_RESERVATION_BACKLOG_SQL`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private val ENTRY_RESERVATION_BACKLOG_SQL =
            """SELECT count(*) AS due_count,
                       COALESCE(max(EXTRACT(EPOCH FROM (statement_timestamp()-eligible_at))*1000),0)::bigint AS oldest_millis
                FROM (
                    SELECT updated_at+(? * interval '1 millisecond') AS eligible_at
                    FROM voucher_pool_entries WHERE state IN ('REDEEMED','RELEASED','REVOKED','EXPIRED')
                    UNION ALL
                    SELECT reservation_expires_at+(? * interval '1 millisecond') AS eligible_at
                    FROM voucher_pool_reservations WHERE state IN ('EXPIRED','RELEASED','REVOKED')
                ) due WHERE eligible_at<=statement_timestamp()""".trimIndent()
        /**
         * `AUDIT_BACKLOG_SQL`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val AUDIT_BACKLOG_SQL =
            """SELECT count(*) AS due_count,
                       COALESCE(max(EXTRACT(EPOCH FROM (statement_timestamp()-(created_at+(? * interval '1 millisecond'))))*1000),0)::bigint AS oldest_millis
                FROM voucher_pool_audits
                WHERE created_at+(? * interval '1 millisecond')<=statement_timestamp()""".trimIndent()

    }
}
