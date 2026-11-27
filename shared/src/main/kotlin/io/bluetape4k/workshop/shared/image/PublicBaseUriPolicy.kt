package io.bluetape4k.workshop.shared.image

import java.net.URI

/** 공개 이미지 base URI의 scheme, authority와 경로 안전성 정책입니다. */
data class PublicBaseUriPolicy(
    val allowInsecurePublicBaseUrl: Boolean = false,
    val allowLocalStorageRemotePublicBaseUrl: Boolean = false,
    val loopbackHosts: Set<String> = setOf("localhost", "127.0.0.1", "::1"),
) {

    /** 정책을 통과한 URI를 반환하고, 실패 시 모든 메시지에 설정 키를 포함합니다. */
    fun validate(publicBaseUrl: String, storageBackendIsLocal: Boolean = false): URI {
        val uri = try {
            URI.create(publicBaseUrl)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("publicBaseUrl must be a valid URI", failure)
        }
        val host = uri.host?.lowercase()
        val normalizedLoopbacks = loopbackHosts.map(String::lowercase).toSet()
        val isLoopback = host != null && host in normalizedLoopbacks

        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "publicBaseUrl scheme must be http or https"
        }
        require(!host.isNullOrBlank()) { "publicBaseUrl host must be present" }
        require(uri.userInfo == null) { "publicBaseUrl must not include userInfo" }
        require(uri.query == null) { "publicBaseUrl must not include a query" }
        require(uri.fragment == null) { "publicBaseUrl must not include a fragment" }

        val rawPath = uri.rawPath.orEmpty()
        require(!rawPath.contains("..")) { "publicBaseUrl path must not traverse" }
        require(!rawPath.contains("%2e", ignoreCase = true)) {
            "publicBaseUrl path must not contain encoded dot segments"
        }

        if (uri.scheme.equals("http", ignoreCase = true)) {
            require(isLoopback || allowInsecurePublicBaseUrl) {
                "publicBaseUrl must use https for remote hosts"
            }
        }
        if (storageBackendIsLocal && !isLoopback) {
            require(allowLocalStorageRemotePublicBaseUrl) {
                "publicBaseUrl remote URL is not allowed for local storage"
            }
        }
        return uri
    }
}
