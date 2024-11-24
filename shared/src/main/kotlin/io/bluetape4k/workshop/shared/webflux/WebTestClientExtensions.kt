package io.bluetape4k.workshop.shared.webflux

import org.springframework.test.web.reactive.server.WebTestClient

fun WebTestClient.httpGet(uri: String) =
    get()
        .uri(uri)
        .exchange()
        .expectStatus().is2xxSuccessful

fun <T: Any> WebTestClient.httpPost(uri: String, value: T) =
    post()
        .uri(uri)
        .bodyValue(value)
        .exchange()
        .expectStatus().is2xxSuccessful

fun WebTestClient.httpDelete(uri: String) =
    delete().uri(uri)
        .exchange()
        .expectStatus().is2xxSuccessful
