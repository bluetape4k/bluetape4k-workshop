package io.bluetape4k.workshop.imageprocessing.profile.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.imageprocessing.profile.service.RecordingProfileImageStorage
import org.junit.jupiter.api.Test

class PublicProfileImageControllerTest {

    @Test
    fun pending_image_is_served_with_no_store_cache() = runSuspendIO {
        val storage = RecordingProfileImageStorage()
        val key = ImageObjectKey.of("profile-images/user-1/upload-a/pending", "blurred.jpg")
        storage.upload(key, byteArrayOf(1, 2, 3), UploadOptions(contentType = "image/jpeg"))
        val controller = PublicProfileImageController(storage)

        val response = controller.get("profile-images/user-1/upload-a/pending/blurred.jpg")

        response.statusCode.value() shouldBeEqualTo 200
        response.headers.cacheControl shouldBeEqualTo "no-store"
        response.body?.toList() shouldBeEqualTo listOf(1.toByte(), 2.toByte(), 3.toByte())
    }

    @Test
    fun private_original_path_is_not_served() = runSuspendIO {
        val controller = PublicProfileImageController(RecordingProfileImageStorage())

        val response = controller.get("profile-images/user-1/upload-a/private/original/safe.jpg")

        response.statusCode.value() shouldBeEqualTo 404
    }
}
