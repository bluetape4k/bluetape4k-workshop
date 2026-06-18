# CBOR in Spring Boot MVC

[English](README.md) | 한국어

이 모듈은 Spring MVC가 `Course` resource를 JSON 대신 CBOR(`application/cbor`)로 반환하는 방법을 보여 줍니다. Controller code는 평범하게 유지하고, 핵심은 `JacksonCborHttpMessageConverter`를 MVC message converter chain에 등록하는 것입니다.

## 아키텍처

![CBOR in Spring Boot MVC architecture](../../docs/images/readme-diagrams/spring-boot-cbor-mvc-readme-architecture-01.png)

`CborConfig`는 CBOR converter를 등록하고 in-memory `CourseRepository`를 준비합니다. 테스트는 CBOR encoder/decoder를 설정한 `RestTemplate`, `RestClient`, `WebClient`로 endpoint를 호출합니다.

## 요청 흐름

![CBOR in Spring Boot MVC request flow](../../docs/images/readme-diagrams/spring-boot-cbor-mvc-sequence-01.png)

이 예제는 일반 JSON 대신 바이너리 JSON 형식인 CBOR(Concise Binary Object Representation)을 REST API 통신 형식으로 사용하는 방법을 보여 줍니다.

## CBOR 개념

**CBOR(Concise Binary Object Representation)**은 RFC 7049로 표준화된 바이너리 JSON 형식입니다.

| 기능 | JSON | CBOR |
|------|------|------|
| 형식 | 텍스트(UTF-8) | 바이너리 |
| 페이로드 크기 | 상대적으로 큼 | 20-50% 더 작음 |
| 파싱 속도 | 보통 | 빠름(직접 바이너리 파싱) |
| Content-Type | `application/json` | `application/cbor` |
| 사람이 읽기 쉬움 | 예 | 아니오(디코더 필요) |

Jackson의 `CBORMapper`를 사용합니다. Spring MVC의 `HttpMessageConverter` 시스템에 `JacksonCborHttpMessageConverter`를 등록하면 JSON과 같은 컨트롤러 코드로 CBOR를 지원할 수 있습니다.

## 도메인 모델

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

## 주요 기능

| 기능 | 구현 위치 | 설명 |
|------|----------|------|
| CBOR 컨버터 등록 | `CborConfig` | `JacksonCborHttpMessageConverter` 빈 + `WebMvcConfigurer` 등록 |
| Course 조회 | `CourseController.course()` | `GET /courses/{id}` — CBOR 직렬화 응답 |
| 인메모리 저장소 | `CourseRepository` | `Map<Int, Course>` 기반 단순 저장소 |

## API 엔드포인트

| 메서드 | 경로 | 설명 | Content-Type |
|--------|------|------|-------------|
| `GET` | `/courses/{id}` | 특정 course를 조회합니다. | `application/cbor` |

## 사용 예제

### CBOR 요청(curl)

```bash
# Receive a CBOR response (binary, saved to a file)
curl -H "Accept: application/cbor" http://localhost:8080/courses/1 -o course.cbor

# Receive a JSON response (falls back to JSON when no Accept header is specified)
curl http://localhost:8080/courses/1
```

### 테스트 코드에서 CBOR TestRestTemplate 사용

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

## 설정

### `cbor` 프로필 활성화

`JacksonCborHttpMessageConverter`는 `@Profile("cbor")` 조건으로 등록됩니다.

```yaml
# application.yml
spring:
  profiles:
    active: cbor
```
