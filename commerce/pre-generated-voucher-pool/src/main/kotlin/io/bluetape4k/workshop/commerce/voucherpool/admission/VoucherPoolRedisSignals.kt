@file:Suppress("MatchingDeclarationName")

package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.io.digest.Digesters
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import java.nio.ByteBuffer

/**
 * advisory Redis state를 위한 opaque하고 operation-separated된 key를 생성합니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 */
internal class VoucherPoolRedisSignalKeyFactory(
    /**
     * `version`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val version: Int = 1,
) {

    private companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val DOMAIN = "voucher-pool-redis-signal"
    }

    init {
        version.requirePositiveNumber { "Redis signal key version must be positive" }
    }

    /**
     * admissionKey은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param namespace 업무 처리에 필요한 namespace 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param principalDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    fun admissionKey(namespace: AdmissionNamespace, principalDigest: ByteArray): String {
        require(principalDigest.isNotEmpty()) { "principalDigest must not be empty" }

        val digest = Digesters.SHA256.newSession()
        digest.update(field(DOMAIN))
        digest.update(field(namespace.name))
        digest.update(field(principalDigest))
        return "v$version:${digest.digestHex()}"
    }

    /**
     * field은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun field(value: String): ByteArray = field(value.toUtf8Bytes())

    /**
     * field은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun field(value: ByteArray): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES + value.size)
            .putInt(value.size)
            .put(value)
            .array()

}
