# Spring Data R2DBC Coroutines

[한국어](README.ko.md) | English

This module exposes a coroutine REST API over Spring Data R2DBC and an in-memory H2
database by default. It demonstrates bluetape4k `*Suspending` extensions for `R2dbcEntityOperations`
so repository code can stay suspend/`Flow` based instead of manually converting Reactor
publishers.

The application uses the H2 settings in `application.yml` by default, while tests activate the
`postgres` profile and use the real PostgreSQL Testcontainer from
`PostgreSQLServer.Launcher.postgres`. The `spring.r2dbc.*` properties are wired dynamically from
the fixture's mapped port and credentials, and `spring.sql.init` runs `schema.sql` so schema
bootstrap stays consistent across both environments.

## What this example shows

![R2DBC coroutine architecture](../../docs/images/readme-diagrams/spring-data-r2dbc-coroutines-readme-architecture-01.png)

`PostController` publishes `/posts` endpoints, `DatabaseInitializer` seeds two posts and
four comments on `ApplicationReadyEvent`, and `schema.sql` creates the `posts`, `comments`,
and `members` tables for H2 and PostgreSQL R2DBC connections.

## Domain ERD

![R2DBC coroutine ERD](../../docs/images/readme-diagrams/spring-data-r2dbc-coroutines-readme-erd-01.png)

`comments.post_id` stores the post relationship used by `CommentRepository`, but the schema
does not declare a foreign key. `MemberRepository` demonstrates a plain
`CoroutineCrudRepository` over the separate `members` table.

## API and repository paths

| Endpoint or method | Repository path |
|---|---|
| `GET /posts` | `PostRepository.findAll()` returns `Flow<Post>`. |
| `GET /posts/{id}` | `findOneByIdOrNullSuspending(id)` and `PostNotFoundException` on miss. |
| `POST /posts` | `insertSuspending(post)` returns the inserted entity. |
| `GET /posts/{postId}/comments` | `selectSuspending<Comment>(Query)` returns `Flow<Comment>`. |
| `GET /posts/{postId}/comments/count` | `countSuspending<Comment>(Query)` returns the matching row count. |
| `MemberRepository` | `CoroutineCrudRepository<Member, Long>` for direct Spring Data coroutine CRUD. |

## Build and test

```bash
./gradlew :spring-data:r2dbc-coroutines:test
```

Tests require Docker because they start a PostgreSQL container. Running the application directly
uses the H2 fallback unless another profile is supplied.

## References

- [Spring Data R2DBC reference](https://docs.spring.io/spring-data/relational/reference/r2dbc.html)
- [Kotlin coroutines with Spring](https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html)
