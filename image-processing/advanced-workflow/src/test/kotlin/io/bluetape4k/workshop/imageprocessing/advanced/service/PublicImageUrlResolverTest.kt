package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties
import org.junit.jupiter.api.Test

class PublicImageUrlResolverTest {

    @Test
    fun `resolver composes unsigned public url from object key`() {
        val resolver = PublicImageUrlResolver(
            properties = testProperties(publicBaseUrl = "https://cdn.example.com/images/"),
            storageProperties = ImageStorageProperties(backend = ImageStorageProperties.Backend.S3),
        )

        val url = resolver.resolve(ImageObjectKey.of("images/abc/variants", "thumb-128.webp"))

        url shouldBeEqualTo "https://cdn.example.com/images/images/abc/variants/thumb-128.webp"
    }

    @Test
    fun `resolver rejects remote public base url with local storage by default`() {
        assertFailsWith<IllegalArgumentException> {
            PublicImageUrlResolver(
                properties = testProperties(publicBaseUrl = "https://cdn.example.com/images"),
                storageProperties = ImageStorageProperties(backend = ImageStorageProperties.Backend.LOCAL),
            )
        }
    }

    @Test
    fun `resolver rejects insecure non-loopback base url by default`() {
        assertFailsWith<IllegalArgumentException> {
            PublicImageUrlResolver(
                properties = testProperties(
                    publicBaseUrl = "http://cdn.example.com/images",
                    allowLocalStorageRemotePublicBaseUrl = true,
                ),
                storageProperties = ImageStorageProperties(backend = ImageStorageProperties.Backend.LOCAL),
            )
        }
    }

    @Test
    fun `resolver rejects userinfo in public base url`() {
        assertFailsWith<IllegalArgumentException> {
            PublicImageUrlResolver(
                properties = testProperties(
                    publicBaseUrl = "https://user@cdn.example.com/images",
                    allowLocalStorageRemotePublicBaseUrl = true,
                ),
                storageProperties = ImageStorageProperties(backend = ImageStorageProperties.Backend.LOCAL),
            )
        }
    }

    @Test
    fun `resolver rejects encoded parent path segment in public base url`() {
        assertFailsWith<IllegalArgumentException> {
            PublicImageUrlResolver(
                properties = testProperties(
                    publicBaseUrl = "https://cdn.example.com/images/%2e%2e/private",
                    allowLocalStorageRemotePublicBaseUrl = true,
                ),
                storageProperties = ImageStorageProperties(backend = ImageStorageProperties.Backend.LOCAL),
            )
        }
    }
}
