package io.bluetape4k.workshop.shared.web

import kotlinx.coroutines.flow.Flow
import org.reactivestreams.Publisher
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

/**
 * Starts a GET request and optionally applies an `Accept` header.
 */
fun RestClient.httpGet(
    uri: String,
    accept: MediaType? = null,
): RestClient.ResponseSpec =
    get()
        .uri(uri)
        .apply { accept?.let { accept(it) } }
        .retrieve()

/**
 * Starts a HEAD request and optionally applies an `Accept` header.
 */
fun RestClient.httpHead(
    uri: String,
    accept: MediaType? = null,
): RestClient.ResponseSpec =
    head()
        .uri(uri)
        .apply { accept?.let { accept(it) } }
        .retrieve()

/**
 * Starts a POST request with an optional object body.
 */
fun RestClient.httpPost(
    uri: String,
    value: Any? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): RestClient.ResponseSpec =
    post()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            value?.let { body(it) }
            accept?.let { accept(it) }
        }
        .retrieve()

/**
 * Starts a POST request with a reactive-streams body.
 */
inline fun <reified T: Any> RestClient.httpPost(
    uri: String,
    publisher: Publisher<T>,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): RestClient.ResponseSpec =
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
inline fun <reified T: Any> RestClient.httpPost(
    uri: String,
    flow: Flow<T>,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): RestClient.ResponseSpec =
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
fun RestClient.httpPut(
    uri: String,
    value: Any? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): RestClient.ResponseSpec =
    put()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            value?.let { body(it) }
            accept?.let { accept(it) }
        }
        .retrieve()

/**
 * Starts a PUT request with a reactive-streams body.
 */
inline fun <reified T: Any> RestClient.httpPut(
    uri: String,
    publisher: Publisher<T>,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): RestClient.ResponseSpec =
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
inline fun <reified T: Any> RestClient.httpPut(
    uri: String,
    flow: Flow<T>,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): RestClient.ResponseSpec =
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
fun RestClient.httpPatch(
    uri: String,
    value: Any? = null,
    contentType: MediaType? = null,
    accept: MediaType? = null,
): RestClient.ResponseSpec =
    patch()
        .uri(uri)
        .apply {
            contentType?.let { contentType(it) }
            value?.let { body(it) }
            accept?.let { accept(it) }
        }
        .retrieve()

/**
 * Starts a DELETE request and optionally applies an `Accept` header.
 */
fun RestClient.httpDelete(
    uri: String,
    accept: MediaType? = null,
): RestClient.ResponseSpec =
    delete()
        .uri(uri)
        .apply { accept?.let { accept(it) } }
        .retrieve()
