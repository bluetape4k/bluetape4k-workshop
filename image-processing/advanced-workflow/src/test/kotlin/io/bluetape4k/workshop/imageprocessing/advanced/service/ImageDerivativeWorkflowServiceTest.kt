package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDetailResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectDTO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectKind
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.ImagePersistenceService
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile

class ImageDerivativeWorkflowServiceTest {

    private val keyFactory = ImageKeyFactory()

    @Test
    fun `workflow stores original and all configured variants`() = runSuspendIO {
        val properties = testProperties(maxInputBytes = 1024)
        val storage = RecordingImageStorage()
        val meterRegistry = SimpleMeterRegistry()
        val service = service(properties, storage, meterRegistry)
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

        val response = service.processUpload(file)

        storage.uploads.size shouldBeEqualTo 4
        response.original.key.startsWith("images/") shouldBeEqualTo true
        response.original.url.startsWith("http://localhost:8080/public-images/images/") shouldBeEqualTo true
        response.thumbnailUrl shouldBeEqualTo response.variants.first { it.name == "thumb-128" }.url
        response.variants.map { it.name } shouldBeEqualTo listOf("thumb-128", "card-320", "detail-1024")
        meterRegistry.counter(ImageDerivativeWorkflowService.METRIC_VARIANT_GENERATED, "variant", "thumb-128").count() shouldBeEqualTo 1.0
    }

    @Test
    fun `workflow rejects invalid content type before storage`() = runSuspendIO {
        val storage = RecordingImageStorage()
        val service = service(testProperties(), storage)
        val file = MockMultipartFile("file", "note.txt", "text/plain", "plain text".encodeToByteArray())

        assertFailsWith<IllegalArgumentException> {
            service.processUpload(file)
        }
        storage.uploads.size shouldBeEqualTo 0
    }

    @Test
    fun `workflow rejects too large input before storage`() = runSuspendIO {
        val storage = RecordingImageStorage()
        val service = service(testProperties(maxInputBytes = 2), storage)
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

        assertFailsWith<IllegalArgumentException> {
            service.processUpload(file)
        }
        storage.uploads.size shouldBeEqualTo 0
    }

    @Test
    fun `workflow cleans up uploaded objects after variant storage failure`() = runSuspendIO {
        val storage = RecordingImageStorage(failOnKeyPart = "card-320")
        val service = service(testProperties(), storage)
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

        assertFailsWith<Exception> {
            service.processUpload(file)
        }

        storage.uploads.map { it.key.fullKey }.size shouldBeEqualTo 2
        storage.deletes.map { it.fullKey } shouldBeEqualTo storage.uploads.map { it.key.fullKey }.asReversed()
    }

    @Test
    fun `workflow returns cached response when asset is already ready`() = runSuspendIO {
        val existingImageId = "existing-image-id"
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
        val cachedDetail = ImageAssetDetailResponse(
            imageId = existingImageId,
            status = ImageAssetStatus.READY,
            original = cachedOriginal,
            variants = listOf(cachedVariant),
        )
        val storage = RecordingImageStorage()
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
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

        val response = service.processUpload(file)

        // No new uploads — served from cache.
        storage.uploads.size shouldBeEqualTo 0
        response.imageId shouldBeEqualTo existingImageId
        response.warnings shouldContain "Returned from cache — asset already processed"
    }

    private fun service(
        properties: io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties,
        storage: RecordingImageStorage,
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
