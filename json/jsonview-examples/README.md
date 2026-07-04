# JsonView in Spring Boot Demo

[한국어](README.ko.md) | English

This module demonstrates how Spring Boot and Jackson `@JsonView` can return the same `ArticleDTO`
through several endpoints while exposing different fields per audience. The example uses
`Jackson.defaultJsonMapper` from `bluetape4k-jackson3`, a coroutine-friendly controller, and WebFlux
tests that assert the serialized response shape.

## Architecture

![JsonView response filtering architecture](../../docs/images/readme-diagrams/json-jsonview-examples-readme-architecture-01.png)

`ArticleController` owns the endpoint-level `@JsonView` declarations. `ArticleDTO` marks the fields
that belong to `Views.Public` and `Views.Analytics`, while `Views.Internal` inherits both. Spring then
hands the selected view to Jackson during response serialization.

## Endpoint view contract

![JsonView endpoint field contract](../../docs/images/readme-diagrams/json-jsonview-examples-readme-view-contract-01.png)

| endpoint | applied view | serialized fields |
|---|---|---|
| `GET /articles` | `Views.Public` | `id`, `title`, `category` |
| `GET /articles/{id}` | none | `id`, `title`, `category`, `content`, `views`, `likes` |
| `GET /articles/{id}/analytics` | `Views.Analytics` | `views`, `likes` |
| `GET /articles/{id}/internal` | `Views.Internal` | `id`, `title`, `category`, `views`, `likes` |

`content` has no `@JsonView` annotation, so it appears on the no-view detail endpoint and is omitted
when a view is active.

## bluetape4k features used

| function | artifact | code location | advantage |
|---|---|---|---|
| `Jackson.defaultJsonMapper` | `bluetape4k-jackson3` | `JacksonConfig.jsonMapper()` | Registers the default Kotlin/Jackson mapper without rebuilding modules in the example |
| `KLoggingChannel` | `bluetape4k-logging` | `ArticleController` and tests | Coroutine-aware lazy logging |
| `httpGet()` and assertions | `shared`, `bluetape4k-assertions` | `ArticleControllerTest` | Compact WebFlux request and response-shape checks |
| Serializable DTO contract | Kotlin/JVM style rule | `ArticleDTO` | Keeps the public response DTO compatible with cache/session/message examples |

## View hierarchy

```kotlin
interface Views {
    interface Public
    interface Analytics
    interface Internal: Public, Analytics
}
```

`Views.Internal` combines the public and analytics fields. It still does not include `content`,
because that field is not assigned to any view.

`ArticleDTO` is declared as a serializable data class, and the controller fixtures use named
arguments so the similarly typed `views` and `likes` fields cannot be swapped accidentally.

## Controller shape

```kotlin
@RestController
@RequestMapping("/articles")
class ArticleController {

    @GetMapping
    @JsonView(Views.Public::class)
    fun getAllArticles(): Flow<ArticleDTO> = articles.values.asFlow()

    @GetMapping("/{id}")
    suspend fun getArticleDetails(@PathVariable(name = "id") id: Long): ArticleDTO? = articles[id]

    @JsonView(Views.Analytics::class)
    @GetMapping("/{id}/analytics")
    suspend fun getArticleAnalytics(@PathVariable id: Long): ArticleDTO? = articles[id]

    @JsonView(Views.Internal::class)
    @GetMapping("/{id}/internal")
    suspend fun getArticleInternal(@PathVariable id: Long): ArticleDTO? = articles[id]
}
```

## Jackson configuration

```kotlin
@Configuration(proxyBeanMethods = false)
class JacksonConfig {
    @Bean
    fun jsonMapper(): JsonMapper = Jackson.defaultJsonMapper
}
```

## Test expectations

```kotlin
val articles = client.httpGet("/articles")
    .expectStatus().is2xxSuccessful
    .expectBodyList<ArticleDTO>()
    .returnResult().responseBody
    .shouldNotBeNull()

articles.forEach { it.views.shouldBeNull() }
articles.forEach { it.likes.shouldBeNull() }

val analytics = client.httpGet("/articles/1/analytics")
    .returnResult<ArticleDTO>().responseBody.awaitSingle()

analytics.id.shouldBeNull()
analytics.views shouldBeEqualTo 1000L
```

## References

* [@JsonView with Spring Boot and Kotlin](https://codersee.com/jsonview-with-spring-boot-and-kotlin/)
* [Jackson @JsonView documentation](https://github.com/FasterXML/jackson-annotations/wiki/Jackson-Annotations#jsonview)
* [Spring MVC @JsonView support](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/jackson.html)
