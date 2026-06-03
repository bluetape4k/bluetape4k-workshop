# JsonView in Spring Boot Demo

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **JsonView in Spring Boot Demo** as a runnable JSON serialization workflow workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![JsonView in Spring Boot Demo Graphviz architecture diagram](../../docs/images/readme-diagrams/json-jsonview-examples-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.json` as the source of truth when comparing this README with the code.

## Sequence Diagram

Let's learn how to filter response data using `@JsonView` when responding in Spring Boot REST API.
Simplify KotlinModule + JavaTimeModule configuration by registering bluetape4k's `Jackson.defaultJsonMapper` as `@Bean`.

## bluetape4k features used

| function | artifact | code location | advantage |
|---|---|---|---|
| `Jackson.defaultJsonMapper` | `bluetape4k-jackson3` | `JacksonConfig.jsonMapper()` | KotlinModule + JavaTimeModule pre-registration — no manual setup required |
| `KLogging` | `bluetape4k-logging` | companion object | Lazy lambda logging |
| `shouldBeNull()` / `shouldBeEqualTo` | `bluetape4k-assertions` | controller test | Kluent style assertion |

## Before / After

```kotlin
// Before — Manual JsonMapper setup
@Bean
fun jsonMapper(): JsonMapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .addModule(JavaTimeModule())
    .build()

// After — bluetape4k Jackson.defaultJsonMapper
@Bean
fun jsonMapper(): JsonMapper = Jackson.defaultJsonMapper
```

![Before / After diagram](../../docs/images/readme-diagrams/json-jsonview-examples-diagram-02.png)

## Main features

- **Response field screening** — Control exposed fields differently for each endpoint while reusing the same DTO class
- **Hierarchical view inheritance** — `Internal` inherits `Public` and `Analytics` simultaneously, exposing all fields
- **Automatically exclude fields not specified in the view** — Fields defined without `@JsonView`, such as the `content` field, are excluded from the response when applying the view.
- **Controller level declaration** — If you declare `@JsonView` in a method, it will be automatically filtered out during the serialization step.
- **Spring WebFlux coroutine support** — works with `suspend fun` and `Flow` return types

## View hierarchy

```kotlin
interface Views {
interface Public // Public information: id, title, category
interface Analytics // Statistical information: views, likes
interface Internal: Public, Analytics // Internal information: includes both public + statistics
}
```

| view interface | exposure field | explanation |
|---|---|---|
| `Views.Public` | `id`, `title`, `category` | For external public use — only identifiers and basic information are exposed |
| `Views.Analytics` | `views`, `likes` | For statistics and analysis — only the number of views and likes is exposed |
| `Views.Internal` | `id`, `title`, `category`, `views`, `likes` | For internal administrators — both Public + Analytics exposed |
| (no view) | `content` | Always excluded because it is not included in any view |

## API endpoint

| method | channel | Applied view | response field |
|---|---|---|---|
| `GET` | `/articles` | `Views.Public` | `id`, `title`, `category` |
| `GET` | `/articles/{id}` | (no view) | All fields (including `content`) |
| `GET` | `/articles/{id}/analytics` | `Views.Analytics` | `views`, `likes` |
| `GET` | `/articles/{id}/internal` | `Views.Internal` | `id`, `title`, `category`, `views`, `likes` |

## Usage example

### DTO definition — @JsonView applied

```kotlin
data class ArticleDTO(
    @JsonView(Views.Public::class)
    val id: Long?,

    @JsonView(Views.Public::class)
    val title: String?,

    @JsonView(Views.Public::class)
    val category: String?,

val content: String?, // @JsonView none — always excluded when applying a view

    @JsonView(Views.Analytics::class)
    val views: Long?,

    @JsonView(Views.Analytics::class)
    val likes: Long?,
)
```

### Controller — Endpoint-specific view declarations

```kotlin
@RestController
@RequestMapping("/articles")
class ArticleController {

// Full list: Apply public view → expose only id, title, and category
    @GetMapping
    @JsonView(Views.Public::class)
    fun getAllArticles(): Flow<ArticleDTO> = articles.values.asFlow()

// Detailed query: No view → All fields exposed (including content)
    @GetMapping("/{id}")
    suspend fun getArticleDetails(@PathVariable id: Long): ArticleDTO? = articles[id]

// Statistics inquiry: Analytics view → Only views and likes are exposed
    @JsonView(Views.Analytics::class)
    @GetMapping("/{id}/analytics")
    suspend fun getArticleAnalytics(@PathVariable id: Long): ArticleDTO? = articles[id]

// Internal view: Internal view → Exposure of both Public + Analytics fields
    @JsonView(Views.Internal::class)
    @GetMapping("/{id}/internal")
    suspend fun getArticleInternal(@PathVariable id: Long): ArticleDTO? = articles[id]
}
```

### Jackson settings

```kotlin
@Configuration(proxyBeanMethods = false)
class JacksonConfig {

// bluetape4k Jackson.defaultJsonMapper: KotlinModule + JavaTimeModule pre-registration
    @Bean
    fun jsonMapper(): JsonMapper = Jackson.defaultJsonMapper
}
```

### Test example

```kotlin
// Public view: views, likes return null
val articles = client.httpGet("/articles")
    .expectStatus().is2xxSuccessful
    .expectBodyList<ArticleDTO>()
    .returnResult().responseBody!!

articles.forEach { it.views.shouldBeNull() } // Check for exclusion of Analytics fields
articles.forEach { it.likes.shouldBeNull() }

// Analytics view: id, title, category return as null
val analytics = client.httpGet("/articles/1/analytics")
    .returnResult<ArticleDTO>().responseBody.awaitSingle()

analytics.id.shouldBeNull()
analytics.views shouldBeEqualTo 1000L

// Internal view: Public + Analytics return all fields
val internal = client.httpGet("/articles/1/internal")
    .returnResult<ArticleDTO>().responseBody.awaitSingle()

internal.id shouldBeEqualTo 1
internal.views shouldBeEqualTo 1000L
```

## reference

* [@JsonView with Spring Boot and Kotlin](https://codersee.com/jsonview-with-spring-boot-and-kotlin/)
* [Jackson @JsonView official documentation](https://github.com/FasterXML/jackson-annotations/wiki/Jackson-Annotations#jsonview)
* [Spring MVC @JsonView support](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/jackson.html)
