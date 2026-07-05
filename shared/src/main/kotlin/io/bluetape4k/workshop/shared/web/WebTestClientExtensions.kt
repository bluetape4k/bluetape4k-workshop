package io.bluetape4k.workshop.shared.web

import kotlinx.coroutines.flow.Flow
import org.reactivestreams.Publisher
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.body

/**
 * Exchanges a GET request and optionally verifies the expected status.
 */
fun WebTestClient.httpGet(
    uri: String,
    httpStatus: HttpStatus? = null,
    accept: MediaType? = null,
): WebTestClient.ResponseSpec =
    get()
        .uri(uri)
        .apply { accept?.let { accept(it) } }
        .exchange()
        .apply {
            httpStatus?.let { expectStatus().isEqualTo(it) }
        }

/**
 * Exchanges a HEAD request and optionally verifies the expected status.
 */
fun WebTestClient.httpHead(
    uri: String,
    httpStatus: HttpStatus? = null,
    accept: MediaType? = null,
): WebTestClient.ResponseSpec =
    head()
        .uri(uri)
        .apply { accept?.let { accept(it) } }
        .exchange()
        .apply {
            httpStatus?.let { expectStatus().isEqualTo(it) }
        }

/**
 * Exchanges a POST request with an optional object body.
 */
fun WebTestClient.httpPost(
    uri: String,
    value: Any? = null,
    httpStatus: HttpStatus? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebTestClient.ResponseSpec =
    post()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            value?.let { bodyValue(it) }
            accept?.let { accept(it) }
        }
        .exchange()
        .apply {
            httpStatus?.let { expectStatus().isEqualTo(it) }
        }

/**
 * Exchanges a POST request with a reactive-streams body.
 */
inline fun <reified T: Any> WebTestClient.httpPost(
    uri: String,
    publisher: Publisher<T>,
    httpStatus: HttpStatus? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebTestClient.ResponseSpec =
    post()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            accept?.let { accept(it) }
        }
        .body(publisher)
        .exchange()
        .apply {
            httpStatus?.let { expectStatus().isEqualTo(it) }
        }

/**
 * Exchanges a POST request with a Kotlin [Flow] body.
 */
inline fun <reified T: Any> WebTestClient.httpPost(
    uri: String,
    flow: Flow<T>,
    httpStatus: HttpStatus? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebTestClient.ResponseSpec =
    post()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            accept?.let { accept(it) }
        }
        .body(flow)
        .exchange()
        .apply {
            httpStatus?.let { expectStatus().isEqualTo(it) }
        }

/**
 * Exchanges a PUT request with an optional object body.
 */
fun WebTestClient.httpPut(
    uri: String,
    value: Any? = null,
    httpStatus: HttpStatus? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebTestClient.ResponseSpec =
    put()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            value?.let { bodyValue(it) }
            accept?.let { accept(it) }
        }
        .exchange()
        .apply {
            httpStatus?.let { expectStatus().isEqualTo(it) }
        }

/**
 * Exchanges a PUT request with a reactive-streams body.
 */
inline fun <reified T: Any> WebTestClient.httpPut(
    uri: String,
    publisher: Publisher<T>,
    httpStatus: HttpStatus? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebTestClient.ResponseSpec =
    put()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            accept?.let { accept(it) }
        }
        .body(publisher)
        .exchange()
        .apply {
            httpStatus?.let { expectStatus().isEqualTo(it) }
        }

/**
 * Exchanges a PUT request with a Kotlin [Flow] body.
 */
inline fun <reified T: Any> WebTestClient.httpPut(
    uri: String,
    flow: Flow<T>,
    httpStatus: HttpStatus? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebTestClient.ResponseSpec =
    put()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            accept?.let { accept(it) }
        }
        .body(flow)
        .exchange()
        .apply {
            httpStatus?.let { expectStatus().isEqualTo(it) }
        }

/**
 * Exchanges a PATCH request with an optional object body.
 */
fun WebTestClient.httpPatch(
    uri: String,
    value: Any? = null,
    httpStatus: HttpStatus? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebTestClient.ResponseSpec =
    patch()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            value?.let { bodyValue(it) }
            accept?.let { accept(it) }
        }
        .exchange()
        .apply {
            httpStatus?.let { expectStatus().isEqualTo(it) }
        }

/**
 * Exchanges a DELETE request and optionally verifies the expected status.
 */
fun WebTestClient.httpDelete(
    uri: String,
    httpStatus: HttpStatus? = null,
    accept: MediaType? = null,
): WebTestClient.ResponseSpec =
    delete()
        .uri(uri)
        .apply { accept?.let { accept(it) } }
        .exchange()
        .apply {
            httpStatus?.let { expectStatus().isEqualTo(it) }
        }
