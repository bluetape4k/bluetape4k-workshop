package io.bluetape4k.workshop.textmoderation.web

import io.bluetape4k.workshop.textmoderation.model.ModerationRequest
import io.bluetape4k.workshop.textmoderation.model.ModerationResponse
import io.bluetape4k.workshop.textmoderation.service.TextModerationService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * text moderation request 를 처리하는 HTTP boundary 입니다.
 */
@RestController
@RequestMapping("/api/moderation", produces = [MediaType.APPLICATION_JSON_VALUE])
class TextModerationController(private val service: TextModerationService) {

    @PostMapping("/analyze", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun analyze(@RequestBody request: ModerationRequest): ModerationResponse =
        service.analyze(request.text)
}
