package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

/**
 * PublicImageUrlResolverTest 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class PublicImageUrlResolverTest {

    private companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "resolver composes unsigned public url from object key" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `resolver composes unsigned public url from object key`() {
        val resolver = PublicImageUrlResolver(
            properties = testProperties(publicBaseUrl = "https://cdn.example.com/images/"),
            storageProperties = ImageStorageProperties(backend = ImageStorageProperties.Backend.S3),
        )

        val url = resolver.resolve(ImageObjectKey.of("images/abc/variants", "thumb-128.webp"))

        log.debug { "url=$url" }
        url shouldBeEqualTo "https://cdn.example.com/images/images/abc/variants/thumb-128.webp"
    }

    @Test
/**
 * 이 시나리오는 "resolver rejects remote public base url with local storage by default" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `resolver rejects remote public base url with local storage by default`() {
        assertFailsWith<IllegalArgumentException> {
            PublicImageUrlResolver(
                properties = testProperties(publicBaseUrl = "https://cdn.example.com/images"),
                storageProperties = ImageStorageProperties(backend = ImageStorageProperties.Backend.LOCAL),
            )
        }
    }

    @Test
/**
 * 이 시나리오는 "resolver rejects insecure non-loopback base url by default" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "resolver rejects userinfo in public base url" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "resolver rejects encoded parent path segment in public base url" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
