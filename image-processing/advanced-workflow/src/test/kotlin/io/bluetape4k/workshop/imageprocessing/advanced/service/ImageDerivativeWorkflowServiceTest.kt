package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile

class ImageDerivativeWorkflowServiceTest {

    private val keyFactory = ImageKeyFactory()

    @Test
    fun `workflow stores original and all configured variants`() = runTest {
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
    fun `workflow rejects invalid content type before storage`() = runTest {
        val storage = RecordingImageStorage()
        val service = service(testProperties(), storage)
        val file = MockMultipartFile("file", "note.txt", "text/plain", "plain text".encodeToByteArray())

        assertFailsWith<IllegalArgumentException> {
            service.processUpload(file)
        }
        storage.uploads.size shouldBeEqualTo 0
    }

    @Test
    fun `workflow rejects too large input before storage`() = runTest {
        val storage = RecordingImageStorage()
        val service = service(testProperties(maxInputBytes = 2), storage)
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

        assertFailsWith<IllegalArgumentException> {
            service.processUpload(file)
        }
        storage.uploads.size shouldBeEqualTo 0
    }

    @Test
    fun `workflow cleans up uploaded objects after variant storage failure`() = runTest {
        val storage = RecordingImageStorage(failOnKeyPart = "card-320")
        val service = service(testProperties(), storage)
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", SAMPLE_JPEG_BYTES)

        assertFailsWith<Exception> {
            service.processUpload(file)
        }

        storage.uploads.map { it.key.fullKey }.size shouldBeEqualTo 2
        storage.deletes.map { it.fullKey } shouldBeEqualTo storage.uploads.map { it.key.fullKey }.asReversed()
    }

    private fun service(
        properties: io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties,
        storage: RecordingImageStorage,
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): ImageDerivativeWorkflowService =
        ImageDerivativeWorkflowService(
            storage = storage,
            validator = UploadImageValidator(properties),
            processor = StubDerivativeProcessor(keyFactory),
            keyFactory = keyFactory,
            urlResolver = PublicImageUrlResolver(properties, ImageStorageProperties()),
            properties = properties,
            meterRegistry = meterRegistry,
        )
}
