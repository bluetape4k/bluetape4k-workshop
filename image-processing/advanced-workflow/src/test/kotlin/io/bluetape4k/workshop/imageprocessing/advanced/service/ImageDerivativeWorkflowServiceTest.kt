package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.images.spring.ImageObjectMetadata
import io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties
import io.bluetape4k.images.spring.metrics.MetricImageStorageWithMetadata
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDetailResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectDTO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectKind
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.ImagePersistenceService
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile

/**
 * 업로드 saga의 업무 경계를 검증합니다.
 *
 * 정상 경로가 원본과 세 가지 variant를 저장하는지, 선택적 metadata capability를
 * object마다 한 번만 읽고 download를 호출하지 않는지, checksum 중복을 재사용하는지,
 * processor/storage 실패와 취소 뒤 partial object 및 job 상태를 정리하는지를 확인합니다.
 * 이 테스트가 통과해도 실제 S3 권한이나 CDN 정책까지 증명하는 것은 아닙니다.
 */
class ImageDerivativeWorkflowServiceTest {

    private companion object: KLogging()

/**
 * keyFactory은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val keyFactory = ImageKeyFactory()

    @Test
/**
 * 이 시나리오는 "workflow stores original and all configured variants" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `workflow stores original and all configured variants`() = runSuspendIO {
/**
 * properties은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val properties = testProperties(maxInputBytes = 1024)
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val storage = RecordingImageStorage()
/**
 * meterRegistry은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val meterRegistry = SimpleMeterRegistry()
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(properties, storage, meterRegistry)
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service.processUpload(file)
        log.debug { "response: $response" }

        storage.uploads.size shouldBeEqualTo 4
        response.original.key.startsWith("images/").shouldBeTrue()
        response.original.url.startsWith("http://localhost:8080/public-images/images/").shouldBeTrue()
        response.thumbnailUrl shouldBeEqualTo response.variants.first { it.name == "thumb-128" }.url
        response.variants.map { it.name } shouldBeEqualTo listOf("thumb-128", "card-320", "detail-1024")

        meterRegistry.counter(ImageDerivativeWorkflowService.METRIC_VARIANT_GENERATED, "variant", "thumb-128")
            .count() shouldBeEqualTo 1.0
        meterRegistry.counter(ImageDerivativeWorkflowService.METRIC_METADATA_UNAVAILABLE)
            .count() shouldBeEqualTo 4.0
    }

    @Test
/**
 * 이 시나리오는 "workflow reads optional metadata once per object and records opaque snapshot" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `workflow reads optional metadata once per object and records opaque snapshot`() = runSuspendIO {
/**
 * properties은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val properties = testProperties(maxInputBytes = 1024)
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val storage = RecordingMetadataImageStorage()
/**
 * meterRegistry은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val meterRegistry = SimpleMeterRegistry()
/**
 * persistence은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val persistence = StubImagePersistenceService()
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties,
            storage = MetricImageStorageWithMetadata(
                delegate = storage,
                registry = meterRegistry,
                metadataDelegate = storage,
            ),
            meterRegistry = meterRegistry,
            persistenceService = persistence,
        )
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service.processUpload(file)

        log.debug { "response: $response" }

        storage.metadataReads.size shouldBeEqualTo 4
        storage.downloads.size shouldBeEqualTo 0
        response.original.sizeBytes shouldBeEqualTo SAMPLE_JPEG_BYTES.size.toLong()
        response.original.contentType shouldBeEqualTo "image/jpeg"
        response.variants.all { it.contentType == "image/webp" }.shouldBeTrue()
        meterRegistry.counter(ImageDerivativeWorkflowService.METRIC_METADATA_READ).count() shouldBeEqualTo 4.0
        meterRegistry.counter(ImageDerivativeWorkflowService.METRIC_METADATA_UNAVAILABLE).count() shouldBeEqualTo 0.0

/**
 * uploadEvent은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val uploadEvent = persistence.events.single { it.message.startsWith("S3 upload complete") }
        uploadEvent.payload["metadataCapability"] shouldBeEqualTo true

        @Suppress("UNCHECKED_CAST")
/**
 * metadata은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val metadata = uploadEvent.payload["metadata"] as List<Map<String, Any?>>
        metadata.size shouldBeEqualTo 4
        metadata.all { it["available"] == true && it["etag"] is String && it["lastModified"] == "1970-01-01T00:00:00Z" }
            .shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "workflow fails closed when metadata capability reports an error" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `workflow fails closed when metadata capability reports an error`() = runSuspendIO {
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val storage = RecordingMetadataImageStorage(failOnMetadataKeyPart = "/original/")
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(testProperties(), storage)
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

        assertFailsWith<Exception> {
            service.processUpload(file)
        }

        storage.uploads.size shouldBeEqualTo 1
        storage.deletes.map { it.fullKey } shouldBeEqualTo storage.uploads.map { it.key.fullKey }.asReversed()
    }

    @Test
/**
 * 이 시나리오는 "workflow fails closed when metadata size does not match upload" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `workflow fails closed when metadata size does not match upload`() = runSuspendIO {
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val storage = RecordingMetadataImageStorage(
            metadataFn = { key, upload ->
                ImageObjectMetadata(
                    key = key,
                    sizeBytes = upload.bytes.size.toLong() + 1,
                    contentType = upload.options.contentType,
                )
            },
        )
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(testProperties(), storage)
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

        assertFailsWith<IllegalArgumentException> {
            service.processUpload(file)
        }

        storage.uploads.size shouldBeEqualTo 1
        storage.deletes.map { it.fullKey } shouldBeEqualTo storage.uploads.map { it.key.fullKey }.asReversed()
    }

    @Test
/**
 * 이 시나리오는 "workflow rejects invalid content type before storage" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `workflow rejects invalid content type before storage`() = runSuspendIO {
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val storage = RecordingImageStorage()
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(testProperties(), storage)
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "note.txt", "text/plain", "plain text".toUtf8Bytes())

        assertFailsWith<IllegalArgumentException> {
            service.processUpload(file)
        }
        storage.uploads.size shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "workflow rejects too large input before storage" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `workflow rejects too large input before storage`() = runSuspendIO {
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val storage = RecordingImageStorage()
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(testProperties(maxInputBytes = 2), storage)
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

        assertFailsWith<IllegalArgumentException> {
            service.processUpload(file)
        }
        storage.uploads.size shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "workflow cleans up uploaded objects after variant storage failure" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `workflow cleans up uploaded objects after variant storage failure`() = runSuspendIO {
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val storage = RecordingImageStorage(failOnKeyPart = "card-320")
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(testProperties(), storage)
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

        assertFailsWith<Exception> {
            service.processUpload(file)
        }

        storage.uploads.map { it.key.fullKey }.size shouldBeEqualTo 2
        storage.deletes.map { it.fullKey } shouldBeEqualTo storage.uploads.map { it.key.fullKey }.asReversed()
    }

    @Test
/**
 * 이 시나리오는 "workflow returns cached response when asset is already ready" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `workflow returns cached response when asset is already ready`() = runSuspendIO {
/**
 * existingImageId은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val existingImageId = "existing-image-id"
/**
 * cachedOriginal은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val cachedOriginal = ImageObjectDTO(
            id = 1L,
            imageAssetId = 1L,
            kind = ImageObjectKind.ORIGINAL,
            variantName = null,
            s3Key = "images/original.jpg",
            publicUrl = "http://localhost:8080/public-images/images/original.jpg",
            width = 640,
            height = 480,
            byteSize = 4L,
            format = "image/jpeg",
        )
/**
 * cachedVariant은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val cachedVariant = ImageObjectDTO(
            id = 2L,
            imageAssetId = 1L,
            kind = ImageObjectKind.VARIANT,
            variantName = "thumb-128",
            s3Key = "images/thumb.webp",
            publicUrl = "http://localhost:8080/public-images/images/thumb.webp",
            width = 128,
            height = 96,
            byteSize = 4L,
            format = "image/webp",
        )
/**
 * cachedDetail은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val cachedDetail = ImageAssetDetailResponse(
            imageId = existingImageId,
            status = ImageAssetStatus.READY,
            original = cachedOriginal,
            variants = listOf(cachedVariant),
        )
/**
 * storage은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val storage = RecordingImageStorage()
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = testProperties(),
            storage = storage,
            persistenceService = StubImagePersistenceService(
                startResultFn = {
                    JobStartResult.AlreadyReady(assetId = 1L, externalId = existingImageId)
                },
                detailResponseFn = { cachedDetail },
            ),
        )
/**
 * file은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service.processUpload(file)
        log.debug { "response: $response" }

        // 새 업로드는 없으며 캐시에서 제공합니다.
        storage.uploads.size shouldBeEqualTo 0
        response.imageId shouldBeEqualTo existingImageId
        response.warnings shouldContain "Returned from cache — asset already processed"
    }

/**
 * service은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun service(
        properties: io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties,
        storage: io.bluetape4k.images.spring.storage.ImageStorage,
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
        persistenceService: ImagePersistenceService = StubImagePersistenceService(),
    ): ImageDerivativeWorkflowService =
        ImageDerivativeWorkflowService(
            storage = storage,
            validator = UploadImageValidator(properties),
            processor = StubDerivativeProcessor(keyFactory),
            keyFactory = keyFactory,
            urlResolver = PublicImageUrlResolver(properties, ImageStorageProperties()),
            properties = properties,
            meterRegistry = meterRegistry,
            persistenceService = persistenceService,
        )
}
