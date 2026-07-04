package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationDecision
import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageStatus
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.time.Duration

class ProfileImageServiceTest {

    @Test
    fun upload_returns_pending_before_moderation_completes() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
        val file = MockMultipartFile("file", "safe.jpg", "image/jpeg", fixture.sampleJpeg())

        val response = fixture.service.upload("user-1", file)

        response.status shouldBeEqualTo ProfileImageStatus.PENDING_MODERATION
        response.effectiveUrl shouldBeEqualTo response.pendingUrl
        response.effectiveUrl.endsWith("/pending/blurred.jpg") shouldBeEqualTo true
        fixture.storage.uploads.map { it.key.fullKey } shouldBeEqualTo listOf(
            "profile-images/user-1/upload-a/private/original/safe.jpg",
            "profile-images/user-1/upload-a/pending/blurred.jpg",
            "profile-images/user-1/upload-a/public/approved.jpg",
        )
    }

    @Test
    fun approved_completion_switches_effective_url() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
        fixture.service.upload("user-1", MockMultipartFile("file", "safe.jpg", "image/jpeg", fixture.sampleJpeg()))
        awaitModerationRequest(fixture)

        fixture.provider.complete(ModerationDecision.APPROVED, "ok")
        awaitStatus(fixture, ProfileImageStatus.APPROVED)
        val view = fixture.service.find("user-1")

        view.status shouldBeEqualTo ProfileImageStatus.APPROVED
        view.effectiveUrl.endsWith("/public/approved.jpg") shouldBeEqualTo true
        view.pendingUrl shouldBeEqualTo null
    }

    @Test
    fun rejected_completion_switches_to_default_url() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
        fixture.service.upload("user-1", MockMultipartFile("file", "reject.jpg", "image/jpeg", fixture.sampleJpeg()))
        awaitModerationRequest(fixture)

        fixture.provider.complete(ModerationDecision.REJECTED, "unsafe")
        awaitStatus(fixture, ProfileImageStatus.REJECTED)
        val view = fixture.service.find("user-1")

        view.status shouldBeEqualTo ProfileImageStatus.REJECTED
        view.effectiveUrl shouldBeEqualTo view.defaultImageUrl
        view.reason shouldBeEqualTo "unsafe"
    }

    @Test
    fun stale_completion_from_older_upload_is_ignored() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
        fixture.service.upload("user-1", MockMultipartFile("file", "first.jpg", "image/jpeg", fixture.sampleJpeg()))
        awaitModerationRequest(fixture, 1)
        fixture.service.upload("user-1", MockMultipartFile("file", "second.jpg", "image/jpeg", fixture.sampleJpeg()))
        awaitModerationRequest(fixture, 2)

        fixture.provider.complete(ModerationDecision.APPROVED, "older ok")
        delay(50)
        fixture.service.find("user-1").status shouldBeEqualTo ProfileImageStatus.PENDING_MODERATION
        fixture.service.find("user-1").uploadId shouldBeEqualTo "upload-b"

        fixture.provider.complete(ModerationDecision.REJECTED, "second rejected")
        awaitStatus(fixture, ProfileImageStatus.REJECTED)
        fixture.service.find("user-1").reason shouldBeEqualTo "second rejected"
    }

    @Test
    fun moderation_failure_becomes_failed_state() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
        fixture.service.upload("user-1", MockMultipartFile("file", "safe.jpg", "image/jpeg", fixture.sampleJpeg()))
        awaitModerationRequest(fixture)

        fixture.provider.fail(IllegalStateException("provider down"))
        awaitStatus(fixture, ProfileImageStatus.MODERATION_FAILED)
        val view = fixture.service.find("user-1")

        view.effectiveUrl shouldBeEqualTo view.defaultImageUrl
        view.reason shouldBeEqualTo "provider down"
    }

    @Test
    fun invalid_upload_rejects_before_storage() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
        val file = MockMultipartFile("file", "note.txt", "text/plain", "plain".encodeToByteArray())

        assertFailsWith<IllegalArgumentException> {
            fixture.service.upload("user-1", file)
        }
        fixture.storage.uploads.size shouldBeEqualTo 0
    }

    @Test
    fun derivative_storage_failure_cleans_up_partial_uploads() = runSuspendIO {
        val storage = RecordingProfileImageStorage(failOnKeyPart = "pending")
        val fixture = ProfileImageServiceFixture(storage = storage)
        val file = MockMultipartFile("file", "safe.jpg", "image/jpeg", fixture.sampleJpeg())

        assertFailsWith<Exception> {
            fixture.service.upload("user-1", file)
        }

        storage.uploads.map { it.key.fullKey } shouldBeEqualTo listOf("profile-images/user-1/upload-a/private/original/safe.jpg")
        storage.deletes.map { it.fullKey } shouldBeEqualTo listOf("profile-images/user-1/upload-a/private/original/safe.jpg")
        fixture.service.find("user-1").status shouldBeEqualTo ProfileImageStatus.NO_IMAGE
    }

    @Test
    fun no_image_returns_default_view() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()

        val view = fixture.service.find("missing")

        view.status shouldBeEqualTo ProfileImageStatus.NO_IMAGE
        view.effectiveUrl shouldBeEqualTo view.defaultImageUrl
        view.uploadId shouldBeEqualTo null
    }

    @Test
    fun private_original_keys_are_not_publicly_resolvable() {
        val fixture = ProfileImageServiceFixture()
        val resolver = ProfileImageUrlResolver(fixture.properties, io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties())
        val keys = ProfileImageKeyFactory().keys("user-1", "upload-a", "safe.jpg")

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(keys.original)
        }
    }

    @Test
    fun moderation_timeout_becomes_failed_state() = runSuspendIO {
        val fixture = ProfileImageServiceFixture(properties = testProperties(moderationTimeout = Duration.ofMillis(30)))
        fixture.service.upload("user-1", MockMultipartFile("file", "safe.jpg", "image/jpeg", fixture.sampleJpeg()))

        awaitStatus(fixture, ProfileImageStatus.MODERATION_FAILED, attempts = 20)

        fixture.service.find("user-1").effectiveUrl shouldBeEqualTo fixture.service.find("user-1").defaultImageUrl
    }

    private suspend fun awaitModerationRequest(fixture: ProfileImageServiceFixture, count: Int = 1) {
        repeat(20) {
            if (fixture.provider.requests.size >= count) return
            delay(25)
        }
        fixture.provider.requests.size shouldBeEqualTo count
    }

    private suspend fun awaitStatus(
        fixture: ProfileImageServiceFixture,
        status: ProfileImageStatus,
        attempts: Int = 20,
    ) {
        repeat(attempts) {
            if (fixture.service.find("user-1").status == status) return
            delay(25)
        }
        fixture.service.find("user-1").status shouldBeEqualTo status
    }
}
