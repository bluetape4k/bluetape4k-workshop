package io.bluetape4k.workshop.shared.web

import kotlinx.coroutines.flow.Flow
import org.reactivestreams.Publisher
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.body

/**
 * GET request를 시작하고 필요하면 `Accept` header를 적용한다.
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
 * HEAD request를 시작하고 필요하면 `Accept` header를 적용한다.
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
 * optional object body가 있는 POST request를 시작한다.
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
 * reactive-streams body가 있는 POST request를 시작한다.
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
 * Kotlin [Flow] body가 있는 POST request를 시작한다.
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
 * optional object body가 있는 PUT request를 시작한다.
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
 * reactive-streams body가 있는 PUT request를 시작한다.
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
 * Kotlin [Flow] body가 있는 PUT request를 시작한다.
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
 * optional object body가 있는 PATCH request를 시작한다.
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
 * DELETE request를 시작하고 필요하면 `Accept` header를 적용한다.
 */
fun WebClient.httpDelete(
    uri: String,
    accept: MediaType? = null,
): WebClient.ResponseSpec =
    delete()
        .uri(uri)
        .apply { accept?.let { accept(it) } }
        .retrieve()
