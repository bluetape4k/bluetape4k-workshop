# Spring Data Elasticsearch WebFlux

[한국어](README.ko.md) | English

This module exposes a coroutine-based WebFlux REST API for `Book` documents stored in
Elasticsearch. It demonstrates how Spring WebFlux controllers can call Kotlin coroutine
services, Spring Data coroutine repositories, and `ReactiveElasticsearchTemplate` without
blocking the request path.

## What this example shows

![Spring Data Elasticsearch WebFlux architecture](../../docs/images/readme-diagrams/spring-data-elasticsearch-webflux-readme-architecture-01.png)

The application starts a Testcontainers `ElasticsearchOssServer`, configures Spring Data
Elasticsearch with the container URL, and enables reactive Elasticsearch repositories and
auditing. HTTP requests enter `BookController`, pass through validation and service rules,
and are persisted in the `books` index.

## Request flow

![Spring Data Elasticsearch WebFlux request sequence](../../docs/images/readme-diagrams/spring-data-elasticsearch-webflux-readme-sequence-01.png)

The public API is centered on `/v1/books`:

| Method | Path | Service operation |
|---|---|---|
| `GET` | `/v1/books` | Load all books through `BookRepository.findAll()`. |
| `POST` | `/v1/books` | Validate `ModifyBookRequest`, reject duplicate ISBNs, then save. |
| `GET` | `/v1/books/{isbn}` | Resolve a book by ISBN or return `404`. |
| `GET` | `/v1/books/query?title=...&author=...` | Build a native bool query with title and author matches. |
| `PUT` | `/v1/books/{id}` | Replace the stored document while preserving the id. |
| `DELETE` | `/v1/books/{id}` | Delete an existing document or return `404`. |

## Domain and validation

`Book` documents are stored in the `books` index with `title`, `authorName`,
`publicationYear`, `isbn`, and the Spring Data document `id`. `ModifyBookRequest` validates
blank fields and uses `PublicationYearValidator` to reject future publication years.

`BookExceptionHandler` maps `BookNotFoundException` to `404` and duplicated ISBNs to
`400`, so controller methods can stay focused on request-to-service routing.

## Testing notes

The integration tests use `WebTestClient`, recreate the `books` index before each test, and
refresh the index after writes so searches observe the new documents. The test classes are
currently disabled because the Elasticsearch Java client stack in this sample line is
Jackson 2 based while the Spring Boot 4 baseline uses Jackson 3.

## References

- [Spring Data Elasticsearch Reference Documentation](https://docs.spring.io/spring-data/elasticsearch/reference/)
- [Spring WebFlux Reference Documentation](https://docs.spring.io/spring-framework/reference/web/webflux.html)
