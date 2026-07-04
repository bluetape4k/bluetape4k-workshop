package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requireNull
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties
import org.springframework.stereotype.Component
import java.net.URI

@Component
class PublicImageUrlResolver(
    private val properties: ImageProcessingAdvancedProperties,
    storageProperties: ImageStorageProperties,
) {

    private val baseUri: URI = validateBaseUri(properties, storageProperties)
    private val baseUrl: String = baseUri.toString().trimEnd('/')

    fun resolve(key: ImageObjectKey): String =
        "$baseUrl/${key.fullKey}"

    private fun validateBaseUri(
        properties: ImageProcessingAdvancedProperties,
        storageProperties: ImageStorageProperties,
    ): URI {
        val baseUri = URI.create(properties.publicBaseUrl)
        baseUri.scheme.requireNotNull("publicBaseUrl.scheme")
        baseUri.host.requireNotNull("publicBaseUrl.host")
        baseUri.rawQuery.requireNull("publicBaseUrl.query")
        baseUri.rawFragment.requireNull("publicBaseUrl.fragment")
        baseUri.userInfo.requireNull("publicBaseUrl.userInfo")
        require(!baseUri.path.contains("..") && baseUri.rawPath?.contains("%2e", ignoreCase = true) != true) {
            "publicBaseUrl path must not include '..'"
        }

        val loopback = baseUri.host in LOOPBACK_HOSTS
        val https = baseUri.scheme.equals("https", ignoreCase = true)
        val http = baseUri.scheme.equals("http", ignoreCase = true)
        require(https || (http && (loopback || properties.allowInsecurePublicBaseUrl))) {
            "publicBaseUrl must use https unless it is loopback local dev or allowInsecurePublicBaseUrl=true"
        }
        if (storageProperties.backend == ImageStorageProperties.Backend.LOCAL && !loopback) {
            require(properties.allowLocalStorageRemotePublicBaseUrl) {
                "local ImageStorage with remote publicBaseUrl requires allowLocalStorageRemotePublicBaseUrl=true"
            }
        }
        return baseUri
    }

    companion object {
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}
