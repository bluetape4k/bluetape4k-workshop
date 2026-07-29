package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.imageprocessing.advanced.model.AssetMetadataInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageDimensions
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectKind
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 이미지 영속화 통합 테스트의 추상 기반 클래스입니다.
 *
 * [PostgreSQLServer.Launcher.postgres]로 공유 PostgreSQL 컨테이너를 제공하고
 * 테스트 데이터용 편의 builder 메서드를 제공합니다.
 *
 * ## 동작 / 계약
 * - 병렬 테스트 실행은 비활성화되어 있습니다(junit-platform.properties 참조).
 * - 테스트 간 오염을 피하려면 각 테스트 메서드는 고유 checksum을 사용해야 합니다.
 *   [ImagePersistenceService]가 PROPAGATION_REQUIRES_NEW를 사용하고 외부 트랜잭션과
 *   독립적으로 commit하므로 여기서는 `@Rollback`이 동작하지 않습니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractImagePersistenceTest {

    companion object : KLogging() {

        val postgres = PostgreSQLServer.Launcher.postgres

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username.requireNotNull("postgres.username") }
            registry.add("spring.datasource.password") { postgres.password.requireNotNull("postgres.password") }
        }
    }

    /**
     * 실용적인 기본값으로 [AssetMetadataInput]을 만듭니다.
     *
     * 기본적으로 고유 [checksum]을 생성하므로 호출자가 값을 제공하지 않아도
     * 테스트 격리를 자연스럽게 얻습니다.
     */
    protected fun buildMetadata(
        checksum: String = "sha256-${Base58.randomString(12)}",
        filename: String? = "test.jpg",
        contentType: String? = "image/jpeg",
        byteSize: Long? = 12_345L,
        dimensions: ImageDimensions? = ImageDimensions(800, 600),
    ): AssetMetadataInput = AssetMetadataInput(
        checksum = checksum,
        originalFilename = filename,
        contentType = contentType,
        byteSize = byteSize,
        dimensions = dimensions,
    )

    /**
     * ORIGINAL 하나와 VARIANT 하나를 담은 [ImageObjectInput] 목록을 만듭니다.
     * [ImagePersistenceService.recordJobSuccess]에 전달하기에 알맞습니다.
     */
    protected fun buildObjects(assetId: Long): List<ImageObjectInput> = listOf(
        ImageObjectInput(
            kind = ImageObjectKind.ORIGINAL,
            variantName = null,
            s3Key = "uploads/${assetId}/original.jpg",
            publicUrl = "https://cdn.example.com/uploads/${assetId}/original.jpg",
            width = 800,
            height = 600,
            byteSize = 12_345L,
            format = "jpeg",
        ),
        ImageObjectInput(
            kind = ImageObjectKind.VARIANT,
            variantName = "thumbnail",
            s3Key = "uploads/${assetId}/thumbnail.jpg",
            publicUrl = "https://cdn.example.com/uploads/${assetId}/thumbnail.jpg",
            width = 200,
            height = 150,
            byteSize = 2_048L,
            format = "jpeg",
        ),
    )
}
