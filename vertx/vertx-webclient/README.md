# Vert.x WebClient

[한국어](README.ko.md) | English

This module is a set of executable Vert.x WebClient examples. Each test starts a
small local Vert.x server, sends a WebClient request, awaits the response with
`coAwait()`, and verifies the decoded body.

## Architecture

![Vert.x WebClient architecture](../../docs/images/readme-diagrams/vertx-vertx-webclient-readme-architecture-01.png)

## Example Files

| File | Request shape | Response focus |
|---|---|---|
| `SimpleExamples` | `GET /` to an `AbstractVerticle` server | `BodyCodec.string()` returns `Hello World!` |
| `CoroutineExamples` | `GET /` to a `CoroutineVerticle` server | `coAwait()` reads like sequential Kotlin |
| `RequestExamples` | `PUT /simple` with a string buffer | `BodyHandler` reads the request body and returns `OK` |
| `ResponseExamples` | `PUT /` to a JSON server | `BodyCodec.jsonObject()` and `BodyCodec.json(User::class.java)` |

## Core Pattern

```kotlin
val response = WebClient.create(vertx)
    .get(port, "localhost", "/")
    .`as`(BodyCodec.string())
    .send()
    .coAwait()
```

The examples use `withSuspendTestContext` so failed coroutine assertions fail the
Vert.x test context instead of hanging the test.

## Test

```bash
./gradlew :vertx-vertx-webclient:test
```
