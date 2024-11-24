package io.bluetape4k.workshop.exposed.virtualthread

import org.springframework.test.web.reactive.server.WebTestClient

internal fun WebTestClient.get(uri: String) =
    get()
        .uri(uri)
        .exchange()
        .expectStatus().is2xxSuccessful

internal fun <T: Any> WebTestClient.post(uri: String, value: T) =
    post()
        .uri(uri)
        .bodyValue(value)
        .exchange()
        .expectStatus().is2xxSuccessful

internal fun WebTestClient.delete(uri: String) =
    delete().uri(uri)
        .exchange()
        .expectStatus().is2xxSuccessful
