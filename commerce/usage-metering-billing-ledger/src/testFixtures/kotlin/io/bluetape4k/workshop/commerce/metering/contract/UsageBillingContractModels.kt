package io.bluetape4k.workshop.commerce.metering.contract

data class ContractHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun firstHeader(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

fun interface ContractHttpClient {
    fun post(
        path: String,
        username: String,
        idempotencyKey: String?,
        body: String,
    ): ContractHttpResponse
}
