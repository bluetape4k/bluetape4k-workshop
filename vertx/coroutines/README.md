# Vert.x Kotlin Coroutines

[한국어](README.ko.md) | English

This module runs a small movie-rating HTTP service with Vert.x
`CoroutineVerticle`. It keeps the Vert.x event-loop model, but route handlers
are written as suspend functions through `suspendHandler { }`.

## Architecture

![Vert.x coroutine movie-rating architecture](../../docs/images/readme-diagrams/vertx-coroutines-readme-architecture-01.png)

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| `GET` | `/movie/:id` | Reads a movie title from the H2-backed `MOVIE` table |
| `POST` | `/rateMovie/:id?getRating=N` | Inserts a rating row for the movie |
| `GET` | `/getRating/:id` | Returns the average rating from the `RATING` table |

## Coroutine Pattern

`MovieRatingVerticle` extends `CoroutineVerticle`, initializes sample H2 data in
`start()`, and registers routes with `suspendHandler { }`. Each route awaits
Vert.x JDBC futures with `coAwait()`, so query and response code stays
sequential without callback nesting.

## Test Coverage

`MovieRatingVerticeTest` deploys the verticle once, then uses Vert.x WebClient to
verify movie lookup, rating lookup, and rating insertion.

## Test

```bash
./gradlew :vertx-coroutines:test
```

To run the service manually, start `io.bluetape4k.workshop.movierating.main()`
from the IDE and open `http://localhost:8080/movie/starwars`.
