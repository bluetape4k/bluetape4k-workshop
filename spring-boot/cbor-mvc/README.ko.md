# CBOR in Spring Boot MVC

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **CBOR in Spring Boot MVC**를 실행 가능한 Spring Boot 애플리케이션 기능 워크숍 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![CBOR in Spring Boot MVC Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-cbor-mvc-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지를 기준으로 삼습니다.

## 흐름 다이어그램

1. `spring-boot-cbor-mvc`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크숍 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여 줍니다. 없으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![CBOR in Spring Boot MVC sequence diagram](../../docs/images/readme-diagrams/spring-boot-cbor-mvc-sequence-01.png)

이 예제는 일반 JSON 대신 바이너리 JSON 형식인 CBOR(Concise Binary Object Representation)을 REST API 통신 형식으로 사용하는 방법을 보여 줍니다.

## CBOR 직렬화 흐름

![CBOR diagram](../../docs/images/readme-diagrams/spring-boot-cbor-mvc-sequence-01.png)

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
