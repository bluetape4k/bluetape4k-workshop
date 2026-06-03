# CBOR in Spring Boot MVC

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **CBOR in Spring Boot MVC** as a runnable Spring Boot application feature workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![CBOR in Spring Boot MVC Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-cbor-mvc-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springboot` as the source of truth when comparing this README with the code.

## Sequence Diagram

![CBOR in Spring Boot MVC sequence diagram](../../docs/images/readme-diagrams/spring-boot-cbor-mvc-sequence-01.png)

This example shows how to use CBOR (Concise Binary Object Representation), a binary JSON format, as the REST API communication format instead of regular JSON.

## CBOR Concepts

**CBOR (Concise Binary Object Representation)** is a binary JSON format standardized by RFC 7049.

| Feature | JSON | CBOR |
|------|------|------|
| Format | Text (UTF-8) | Binary |
| Payload size | Relatively large | 20-50% smaller |
| Parsing speed | Moderate | Fast (direct binary parsing) |
| Content-Type | `application/json` | `application/cbor` |
| Human-readable | Yes | No (requires a decoder) |

It uses Jackson's `CBORMapper`. Registering `JacksonCborHttpMessageConverter` in Spring MVC's `HttpMessageConverter` system enables CBOR support with the same controller code used for JSON.

## Domain Model

```
Course
├── id: Int
├── name: String
└── students: List<Student>
        ├── id: Int
        ├── firstName / lastName: String
        ├── email: String
        └── phones: List<Phone>
                ├── number: String
                └── type: PhoneType (MOBILE | LANDLINE)
```

## Key Features

| Feature | Implementation Location | Description |
|------|----------|------|
| CBOR converter registration | `CborConfig` | Registers the `JacksonCborHttpMessageConverter` bean + `WebMvcConfigurer` |
| Course lookup | `CourseController.course()` | `GET /courses/{id}` — CBOR-serialized response |
| In-memory repository | `CourseRepository` | Simple repository based on `Map<Int, Course>` |

## API Endpoints

| Method | Path | Description | Content-Type |
|--------|------|------|-------------|
| `GET` | `/courses/{id}` | Retrieves a specific course | `application/cbor` |

## Usage Examples

### CBOR Request (curl)

```bash
# Receive a CBOR response (binary, saved to a file)
curl -H "Accept: application/cbor" http://localhost:8080/courses/1 -o course.cbor

# Receive a JSON response (falls back to JSON when no Accept header is specified)
curl http://localhost:8080/courses/1
```

### Using CBOR TestRestTemplate in Test Code

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CborApplicationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `get course as cbor`() {
        val headers = HttpHeaders().apply {
            accept = listOf(MediaType("application", "cbor"))
        }
        val response = restTemplate.exchange(
            "/courses/1",
            HttpMethod.GET,
            HttpEntity<Any>(headers),
            Course::class.java
        )
        response.statusCode shouldBe HttpStatus.OK
    }
}
```

## Configuration

### Activating the `cbor` Profile

`JacksonCborHttpMessageConverter` is registered conditionally with `@Profile("cbor")`.

```yaml
# application.yml
spring:
  profiles:
    active: cbor
```
