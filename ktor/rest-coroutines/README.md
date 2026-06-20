# ktor-rest-coroutines

[한국어](README.ko.md) | English

Ktor 3 coroutine REST service example for a small book catalog. The module shows how to keep HTTP routing, validation, error mapping, SSE streaming, and NDJSON export explicit without Spring or reactive-stream adapters.

## Architecture

![Ktor REST coroutines architecture](../../docs/images/readme-diagrams/ktor-rest-coroutines-readme-architecture-01.png)

`Application.module` installs the Ktor plugins, creates `BookService`, and injects `InMemoryBookRepository` plus `Jackson3Support`. The storage is intentionally in-memory: `ConcurrentHashMap` holds the current catalog, while `MutableSharedFlow` publishes live events for SSE subscribers.

## Request Flow

![Ktor REST coroutines request sequence](../../docs/images/readme-diagrams/ktor-rest-coroutines-readme-sequence-01.png)

The endpoints are small enough to test independently:

| Path | Contract |
|------|----------|
| `GET /books` | Return every stored `Book` as JSON. |
| `GET /books/{id}` | Return one book or a typed `NotFound` response. |
| `POST /books` | Validate the request body, create the book, emit an SSE event, and return `201 Created`. |
| `PUT /books/{id}` | Validate the body, replace the stored book under the path id, emit an SSE event, and return JSON. |
| `DELETE /books/{id}` | Remove the book and return `204 No Content`. |
| `GET /books/export` | Stream newline-delimited JSON through Jackson 3 and `respondBytesWriter`. |
| `GET /books/stream` | Keep an SSE connection open and send future create/update events. |
| `GET /health` | Return the liveness response used by smoke tests. |

## Important Behaviors

| Area | Implementation detail |
|------|-----------------------|
| JSON REST bodies | Ktor `ContentNegotiation` uses the shared `AppJson` `kotlinx.serialization.json.Json` instance. |
| SSE path | `sse("/books/stream")` is registered at the top-level routing scope so the path is not accidentally doubled. |
| SSE backpressure | `MutableSharedFlow` has `extraBufferCapacity = 64`; repository emits wait up to 5 seconds before dropping the event. |
| NDJSON export | `Jackson3Support` uses `tools.jackson.*` only; it does not use Jackson 2 `com.fasterxml.jackson.*` imports. |
| Error mapping | `StatusPages` maps `NotFound`, `Conflict`, bad requests, and unexpected failures to typed JSON error bodies. |

### Error Response

```json
{"error": "Book not found: id=x", "type": "NotFound"}
```

Known `type` values are `NotFound`, `Conflict`, `BadRequest`, and `Internal`.

## Run

```bash
# Start the server
./gradlew :ktor-rest-coroutines:run

# Run tests
./gradlew :ktor-rest-coroutines:test

# Build the module
./gradlew :ktor-rest-coroutines:build
```

## curl Examples

```bash
curl http://localhost:8080/books

curl -X POST http://localhost:8080/books \
  -H "Content-Type: application/json" \
  -d '{"id":"b-1","title":"Kotlin in Action","author":"Jemerov","year":2017}'

curl http://localhost:8080/books/b-1

curl -X PUT http://localhost:8080/books/b-1 \
  -H "Content-Type: application/json" \
  -d '{"id":"b-1","title":"Kotlin in Action 2e","author":"Jemerov","year":2024}'

curl -X DELETE http://localhost:8080/books/b-1

curl http://localhost:8080/books/export

curl -N http://localhost:8080/books/stream

curl http://localhost:8080/health
```

## Dependencies

```kotlin
implementation(platform(libs.ktor.bom))
implementation(libs.ktor.server.core)
implementation(libs.ktor.server.netty)
implementation(libs.ktor.server.content.negotiation)
implementation(libs.ktor.server.call.logging)
implementation(libs.ktor.server.status.pages)
implementation(libs.ktor.server.sse)
implementation(libs.ktor.serialization.kotlinx.json)
implementation(libs.kotlinx.serialization.json)
implementation(libs.bluetape4k.jackson3)
implementation(libs.jackson3.module.kotlin)
```

## Scope

This is a workshop module, not a production service. It deliberately leaves out authentication, authorization, database persistence, pagination, rate limiting, and SSE reconnect/replay support. The production gap list is tracked in [the design note](../../docs/superpowers/specs/2026-05-25-ktor-rest-coroutines-design.md).
