package io.bluetape4k.workshop.imageprocessing.profile.web

import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageView
import io.bluetape4k.workshop.imageprocessing.profile.service.ProfileImageService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/users/{userId}/profile-image")
/**
 * REST API for uploading and polling a user's effective profile image.
 */
class ProfileImageController(
    private val service: ProfileImageService,
) {

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun upload(
        @PathVariable userId: String,
        @RequestPart("file") file: MultipartFile,
    ): ProfileImageView = service.upload(userId, file)

    @GetMapping
    fun find(@PathVariable userId: String): ProfileImageView = service.find(userId)
}
