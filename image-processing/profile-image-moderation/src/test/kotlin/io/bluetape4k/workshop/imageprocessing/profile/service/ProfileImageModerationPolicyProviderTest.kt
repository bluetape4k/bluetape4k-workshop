package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationDecision
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationRequest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ProfileImageModerationPolicyProviderTest {

    @ParameterizedTest(name = "banned profile marker {0} is rejected")
    @ValueSource(
        strings = [
            "nazi-symbol.jpg",
            "rising-sun-avatar.jpg",
            "rising-sun-flag-profile.jpg",
            "욱일기-profile.jpg",
            "旭日旗-avatar.jpg",
            "hate-text-profile.jpg",
            "reject.jpg",
        ],
    )
    fun banned_profile_symbols_and_text_are_rejected(filename: String) = runSuspendIO {
        val provider = ProfileImageModerationPolicyProvider(testProperties())

        val result = provider.moderate(request(filename))

        result.decision shouldBeEqualTo ModerationDecision.REJECTED
    }

    @ParameterizedTest(name = "safe profile marker {0} is approved")
    @ValueSource(strings = ["family-photo.jpg", "blue-profile.png"])
    fun safe_profile_images_are_approved(filename: String) = runSuspendIO {
        val provider = ProfileImageModerationPolicyProvider(testProperties())

        val result = provider.moderate(request(filename))

        result.decision shouldBeEqualTo ModerationDecision.APPROVED
    }

    private fun request(filename: String): ModerationRequest = ModerationRequest(
        userId = "user-1",
        uploadId = "upload-a",
        originalFilename = filename,
        originalKey = ImageObjectKey.of("profile-images/user-1/upload-a/private/original", "uploaded-profile.jpg"),
    )
}
