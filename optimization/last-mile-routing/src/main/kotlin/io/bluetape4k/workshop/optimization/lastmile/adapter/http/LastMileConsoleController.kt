package io.bluetape4k.workshop.optimization.lastmile.adapter.http

import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
internal class LastMileConsoleController {
    @GetMapping("/last-mile-routing/", produces = [MediaType.TEXT_HTML_VALUE])
    fun index(): ResponseEntity<Resource> = ResponseEntity.ok()
        .cacheControl(CacheControl.noCache())
        .header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'")
        .body(ClassPathResource("static/last-mile-routing/index.html"))
}
