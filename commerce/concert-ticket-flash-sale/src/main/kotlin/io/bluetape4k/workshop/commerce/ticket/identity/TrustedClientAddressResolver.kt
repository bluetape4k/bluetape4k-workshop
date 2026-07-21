package io.bluetape4k.workshop.commerce.ticket.identity

import java.io.Serial
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
        require(trustedNetworks.isNotEmpty()) { "at least one trusted proxy CIDR is required" }
        require(trustedNetworks.none { it.prefixLength == 0 }) { "wildcard trusted proxy CIDR is forbidden" }
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
            require(IP_LITERAL.matches(value))
            InetAddress.getByName(value)
        } catch (failure: Exception) {
            throw InvalidClientAddress(failure)
        }

    private data class Cidr(
        val network: ByteArray,
        val prefixLength: Int,
    ) {
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
                require(parts.size == 2) { "trusted proxy must use CIDR notation" }
                val address =
                    try {
                        require(IP_LITERAL.matches(parts[0]))
                        InetAddress.getByName(parts[0])
                    } catch (failure: Exception) {
                        throw IllegalArgumentException("invalid trusted proxy CIDR", failure)
                    }
                val prefix = parts[1].toIntOrNull() ?: throw IllegalArgumentException("invalid CIDR prefix")
                require(prefix in 0..address.address.size * 8) { "invalid CIDR prefix" }
                return Cidr(address.address, prefix)
            }
        }
    }

    companion object {
        private val IP_LITERAL = Regex("^[0-9a-fA-F:.]+$")
    }
}
