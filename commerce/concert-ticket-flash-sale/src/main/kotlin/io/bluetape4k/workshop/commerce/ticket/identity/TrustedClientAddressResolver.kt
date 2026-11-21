package io.bluetape4k.workshop.commerce.ticket.identity

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotEmpty
import java.io.Serial
import java.io.Serializable
import java.net.InetAddress

/** 신뢰된 network hop에서 들어온 forwarded-address input이 유효하지 않음을 나타냅니다. */
/** 신뢰된 network hop에서 들어온 forwarded-address input이 유효하지 않음을 나타냅니다. */
class InvalidClientAddress(
    cause: Throwable? = null,
): IllegalArgumentException("invalid_client_address", cause) {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** 오른쪽에서 설정된 trusted proxy만 제거해 client를 해석합니다. */
/** 오른쪽에서 설정된 trusted proxy만 제거해 client를 해석합니다. */
class TrustedClientAddressResolver(
    trustedCidrs: List<String>,
) {
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val trustedNetworks = trustedCidrs.map(Cidr::parse)

    init {
        trustedNetworks.requireNotEmpty("trustedNetworks")
        require(trustedNetworks.all { it.prefixLength > 0 }) { "trustedNetworks.hasNoWildcard all it's prefix length greater than 0" }
    }

    /**
     * resolve은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param remoteAddress 업무 처리에 필요한 remoteAddress 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param xForwardedFor 업무 처리에 필요한 xForwardedFor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun resolve(
        remoteAddress: String,
        xForwardedFor: String?,
    ): String {
        val remote = parseAddress(remoteAddress)
        if (trustedNetworks.none { it.contains(remote) }) return remote.hostAddress
        if (xForwardedFor.isNullOrBlank()) return remote.hostAddress

        val hops = xForwardedFor
            .split(',')
            .map { value ->
                if (value != value.trim() && value.trim().isEmpty()) {
                    throw InvalidClientAddress()
                }
                parseAddress(value.trim())
            } + remote

        return hops.asReversed()
            .firstOrNull { hop ->
                trustedNetworks.none { it.contains(hop) }
            }?.hostAddress ?: hops.first().hostAddress
    }

    /**
     * parseAddress은 콘서트 티켓 판매 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
     * 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun parseAddress(value: String): InetAddress =
        try {
            require(IP_LITERAL.matches(value)) { "clientAddress.isIpLiteral matches to IP_LITERAL: $value" }
            InetAddress.getByName(value)
        } catch (failure: Exception) {
            throw InvalidClientAddress(failure)
        }
/**
 * Cidr은 콘서트 티켓 판매에서 전달되는 불변 데이터 모델입니다.
 * @property network 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property prefixLength 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    @Suppress("ArrayInDataClass")
    private data class Cidr(
        /**
         * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val network: ByteArray,
        /**
         * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val prefixLength: Int,
    ): Serializable {
        /**
         * contains은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param address 업무 처리에 필요한 address 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun contains(address: InetAddress): Boolean {
            val candidate = address.address
            if (candidate.size != network.size) return false

            val wholeBytes = prefixLength / 8
            val remainingBits = prefixLength % 8

            if (!(0 until wholeBytes).all { network[it] == candidate[it] }) return false
            if (remainingBits == 0) return true

            val mask = (0xff shl (8 - remainingBits)) and 0xff
            return (network[wholeBytes].toInt() and mask) == (candidate[wholeBytes].toInt() and mask)
        }

        companion object: KLogging() {
            /**
             * parse은 콘서트 티켓 판매 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
             * 분류합니다.
             *
             * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
             *
             * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
             */
            fun parse(value: String): Cidr {
                val parts = value.split('/')
                parts.size.requireEquals(2, "trustedProxy.parts.size")

                val address = try {
                    IP_LITERAL.matches(parts[0]).requireEquals(true, "trustedProxy.address.isIpLiteral")
                    InetAddress.getByName(parts[0])
                } catch (failure: Exception) {
                    throw IllegalArgumentException("invalid trusted proxy CIDR", failure)
                }

                val prefix = parts[1].toIntOrNull() ?: throw IllegalArgumentException("invalid CIDR prefix")

                prefix.requireInRange(0, address.address.size * 8, "trustedProxy.prefix")

                return Cidr(address.address, prefix).apply {
                    log.debug { "Parsed Cidr=$this, value=$value" }
                }
            }
            /**
             * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */

            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        /**
         * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val IP_LITERAL = Regex("^[0-9a-fA-F:.]+$")
    }
}
