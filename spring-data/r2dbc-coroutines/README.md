# Spring Data R2DBC Demo

## 아키텍처 다이어그램

```mermaid
classDiagram
    class Post {
        +String? title
        +String? content
        +Long? id
    }
    class Comment {
        +String? content
        +Long? postId
        +Long? id
    }
    class Member {
        +String name
        +Int age
        +String email
        +Long? id
    }
    class PostRepository {
        <<interface>>
        +findAll() Flow~Post~
        +findById(id) Post?
    }
    class CommentRepository {
        <<interface>>
        +findByPostId(postId) Flow~Comment~
    }
    class MemberRepository {
        <<interface>>
        +findAll() Flow~Member~
    }
    class PostController {
        +getPosts() List~Post~
        +getPost(id) Post
        +createPost(post) Post
    }

    Post "1" --> "0..*" Comment : has
    PostRepository --> Post : 관리
    CommentRepository --> Comment : 관리
    MemberRepository --> Member : 관리
    PostController --> PostRepository : 사용
    PostController --> CommentRepository : 사용
```

```mermaid
sequenceDiagram
    participant 클라이언트 as HTTP 클라이언트
    participant 컨트롤러 as PostController
    participant 저장소 as PostRepository
    participant DB as R2DBC DB

    클라이언트->>컨트롤러: GET /posts
    컨트롤러->>저장소: findAll() [suspend]
    저장소->>DB: SELECT * FROM posts
    DB-->>저장소: Flow~Post~
    저장소-->>컨트롤러: List~Post~
    컨트롤러-->>클라이언트: 200 OK [JSON]

    클라이언트->>컨트롤러: POST /posts
    컨트롤러->>저장소: save(post) [suspend]
    저장소->>DB: INSERT INTO posts
    DB-->>저장소: Post (id 생성)
    저장소-->>컨트롤러: Post
    컨트롤러-->>클라이언트: 201 Created
```

## 참고

* [Spring Data Examples - r2dbc/example](https://github.com/spring-projects/spring-data-examples/tree/main/r2dbc/example)
* [Spring Data Examples - r2dbc/query-by-example](https://github.com/spring-projects/spring-data-examples/tree/main/r2dbc/query-by-example)

* [Spring Data R2DBC and Kotlin Coroutines](https://xebia.com/blog/spring-data-r2dbc-and-kotlin-coroutines/)
* [Kotlin + Spring Webflux + R2DBC](https://dgahn.tistory.com/8)

This projects shows some sample usage of the work-in-progress R2DBC support for Spring Data.

### Interesting bits to look at

- `InfrastructureConfiguration` - sets up a R2DBC `ConnectionFactory` based on the R2DBC H2
  driver (https://github.com/r2dbc/r2dbc-h2[r2dbc-h2]), a `DatabaseClient` and a `R2dbcRepositoryFactory` to eventually
  create a `CustomerRepository`.
- `CustomerRepository` - a standard Spring Data reactive CRUD repository exposing query methods using manually defined
  queries
- `CustomerRepositoryIntegrationTests` - to initialize the database with some setup SQL and the inserting and
  reading `Customer` instances.
- `TransactionalService` - uses declarative transaction to apply a transactional boundary to repository operations.

This project contains samples of Query-by-Example of Spring Data R2DBC.

### Support for Query-by-Example

Query by Example (QBE) is a user-friendly querying technique with a simple interface.
It allows dynamic query creation and does not require to write queries containing field names.
In fact, Query by Example does not require to write queries using SQL at all.

An `Example` takes a data object (usually the entity object or a subtype of it) and a specification how to match
properties.
You can use Query by Example with Repositories.

```java
interface PersonRepository extends ReactiveCrudRepository<Person, Long>, ReactiveQueryByExampleExecutor<Person> {
}
```

```java
Example<Person> example = Example.of(new Person("Jon", "Snow"));
        repo.

findAll(example);


ExampleMatcher matcher = ExampleMatcher.matching().
        .

withMatcher("firstname",endsWith())
        .

withMatcher("lastname",startsWith().

ignoreCase());

Example<Person> example = Example.of(new Person("Jon", "Snow"), matcher);
        repo.

count(example);
```

This example contains shows the usage with `PersonRepositoryIntegrationTests`.
