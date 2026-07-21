package io.bluetape4k.workshop.commerce.ticket.identity

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotEmpty
import java.io.Serial
import java.io.Serializable
import java.net.InetAddress

/** Invalid forwarded-address input from a trusted network hop. */
class InvalidClientAddress(
    cause: Throwable? = null,
) : IllegalArgumentException("invalid_client_address", cause) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Resolves the client by removing only configured trusted proxies from the right. */
class TrustedClientAddressResolver(
    trustedCidrs: List<String>,
) {
    private val trustedNetworks = trustedCidrs.map(Cidr::parse)

    init {
        trustedNetworks.requireNotEmpty("trustedNetworks")
        trustedNetworks.none { it.prefixLength == 0 }.requireEquals(true, "trustedNetworks.hasNoWildcard")
    }

    fun resolve(
        remoteAddress: String,
        xForwardedFor: String?,
    ): String {
        val remote = parseAddress(remoteAddress)
        if (trustedNetworks.none { it.contains(remote) }) return remote.hostAddress
        if (xForwardedFor.isNullOrBlank()) return remote.hostAddress

        val hops =
            xForwardedFor.split(',').map { value ->
                if (value != value.trim() && value.trim().isEmpty()) throw InvalidClientAddress()
                parseAddress(value.trim())
            } + remote
        return hops.asReversed().firstOrNull { hop -> trustedNetworks.none { it.contains(hop) } }
            ?.hostAddress
            ?: hops.first().hostAddress
    }

    private fun parseAddress(value: String): InetAddress =
        try {
            IP_LITERAL.matches(value).requireEquals(true, "clientAddress.isIpLiteral")
            InetAddress.getByName(value)
        } catch (failure: Exception) {
            throw InvalidClientAddress(failure)
        }

    private data class Cidr(
        val network: ByteArray,
        val prefixLength: Int,
    ) : Serializable {
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

        companion object {
            fun parse(value: String): Cidr {
                val parts = value.split('/')
                parts.size.requireEquals(2, "trustedProxy.parts.size")
                val address =
                    try {
                        IP_LITERAL.matches(parts[0]).requireEquals(true, "trustedProxy.address.isIpLiteral")
                        InetAddress.getByName(parts[0])
                    } catch (failure: Exception) {
                        throw IllegalArgumentException("invalid trusted proxy CIDR", failure)
                    }
                val prefix = parts[1].toIntOrNull() ?: throw IllegalArgumentException("invalid CIDR prefix")
                prefix.requireInRange(0, address.address.size * 8, "trustedProxy.prefix")
                return Cidr(address.address, prefix)
            }

            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        private val IP_LITERAL = Regex("^[0-9a-fA-F:.]+$")
    }
}
