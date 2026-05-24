package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.vips.VipsEncodeOptions
import io.bluetape4k.images.vips.VipsImageFormat
import io.bluetape4k.images.vips.coroutines.suspendToBytes
import io.bluetape4k.images.vips.java25.FfmVipsRuntime
import io.bluetape4k.images.vips.java25.suspendFfmVipsImageOf
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageVariantProperties
import io.bluetape4k.workshop.imageprocessing.advanced.model.OriginalImageInfo
import io.bluetape4k.workshop.imageprocessing.advanced.model.ProcessedImageSet
import io.bluetape4k.workshop.imageprocessing.advanced.model.ProcessedImageVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface DerivativeProcessor {
    suspend fun process(bytes: ByteArray, imageId: String): ProcessedImageSet
}

@Component
class FfmVipsDerivativeProcessor(
    private val properties: ImageProcessingAdvancedProperties,
    private val keyFactory: ImageKeyFactory,
) : DerivativeProcessor {

    private val initLock = ReentrantLock()
    private val variantSemaphore = Semaphore(properties.variantConcurrency)
    private val webpOptions = VipsEncodeOptions(quality = WEBP_QUALITY, effort = WEBP_EFFORT)

    override suspend fun process(bytes: ByteArray, imageId: String): ProcessedImageSet = coroutineScope {
        ensureRuntime()

        val originalInfo = suspendFfmVipsImageOf(bytes).use { image ->
            OriginalImageInfo(width = image.width, height = image.height)
        }
        validatePixelBudget(originalInfo)

        val variants = properties.variants.map { variant ->
            async(Dispatchers.IO) {
                variantSemaphore.withPermit {
                    processVariant(bytes, imageId, variant)
                }
            }
        }.awaitAll()

        ProcessedImageSet(original = originalInfo, variants = variants)
    }

    private suspend fun processVariant(
        bytes: ByteArray,
        imageId: String,
        variant: ImageVariantProperties,
    ): ProcessedImageVariant {
        return suspendFfmVipsImageOf(bytes).use { image ->
            image.thumbnail(variant.maxDimension).use { thumbnail ->
                val output = thumbnail.suspendToBytes(VipsImageFormat.WEBP, webpOptions)
                ProcessedImageVariant(
                    name = variant.name,
                    key = keyFactory.variantKey(imageId, variant.name, variant.extension),
                    bytes = output,
                    width = thumbnail.width,
                    height = thumbnail.height,
                    contentType = variant.contentType,
                )
            }
        }
    }

    private fun ensureRuntime() {
        initLock.withLock {
            if (!FfmVipsRuntime.isInitialized) {
                FfmVipsRuntime.init(
                    concurrency = properties.vipsConcurrency,
                    maxPixels = properties.maxPixels,
                )
            }
        }
    }

    private fun validatePixelBudget(originalInfo: OriginalImageInfo) {
        val pixels = originalInfo.width.toLong() * originalInfo.height.toLong()
        require(pixels <= properties.maxPixels) {
            "image pixel count exceeds maxPixels (${properties.maxPixels}): $pixels"
        }
    }

    companion object {
        private const val WEBP_QUALITY = 82
        private const val WEBP_EFFORT = 4
    }
}
