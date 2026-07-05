package io.bluetape4k.workshop.shared.web

import kotlinx.coroutines.flow.Flow
import org.reactivestreams.Publisher
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.body

/**
 * Starts a GET request and optionally applies an `Accept` header.
 */
fun WebClient.httpGet(
    uri: String,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    get()
        .uri(uri)
        .apply { accept?.let { accept(it) } }
        .retrieve()

/**
 * Starts a HEAD request and optionally applies an `Accept` header.
 */
fun WebClient.httpHead(
    uri: String,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    head()
        .uri(uri)
        .apply { accept?.let { accept(it) } }
        .retrieve()

/**
 * Starts a POST request with an optional object body.
 */
fun WebClient.httpPost(
    uri: String,
    value: Any? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    post()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            value?.let { bodyValue(it) }
            accept?.let { accept(it) }
        }
        .retrieve()

/**
 * Starts a POST request with a reactive-streams body.
 */
inline fun <reified T: Any> WebClient.httpPost(
    uri: String,
    publisher: Publisher<T>,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    post()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            accept?.let { accept(it) }
        }
        .body(publisher)
        .retrieve()

/**
 * Starts a POST request with a Kotlin [Flow] body.
 */
inline fun <reified T: Any> WebClient.httpPost(
    uri: String,
    flow: Flow<T>,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    post()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            accept?.let { accept(it) }
        }
        .body(flow)
        .retrieve()

/**
 * Starts a PUT request with an optional object body.
 */
fun WebClient.httpPut(
    uri: String,
    value: Any? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    put()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            value?.let { bodyValue(it) }
            accept?.let { accept(it) }
        }
        .retrieve()

/**
 * Starts a PUT request with a reactive-streams body.
 */
inline fun <reified T: Any> WebClient.httpPut(
    uri: String,
    publisher: Publisher<T>,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    put()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            accept?.let { accept(it) }
        }
        .body(publisher)
        .retrieve()

/**
 * Starts a PUT request with a Kotlin [Flow] body.
 */
inline fun <reified T: Any> WebClient.httpPut(
    uri: String,
    flow: Flow<T>,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    put()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            accept?.let { accept(it) }
        }
        .body(flow)
        .retrieve()

/**
 * Starts a PATCH request with an optional object body.
 */
fun WebClient.httpPatch(
    uri: String,
    value: Any? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    patch()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            value?.let { bodyValue(it) }
            accept?.let { accept(it) }
        }
        .retrieve()

/**
 * Starts a DELETE request and optionally applies an `Accept` header.
 */
fun WebClient.httpDelete(
    uri: String,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    delete()
        .uri(uri)
        .apply { accept?.let { accept(it) } }
        .retrieve()
