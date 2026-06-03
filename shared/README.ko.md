# Bluetape4k Workshop Shared

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Bluetape4k Workshop Shared**를 실행 가능한 shared workshop utilities 워크숍 조각으로 다룹니다. 개발자가 가장 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API를 중심으로 설명합니다.

## 아키텍처 다이어그램

![Bluetape4k Workshop Shared Graphviz architecture diagram](../docs/images/readme-diagrams/shared-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.shared` 패키지를 기준으로 삼습니다.

![Bluetape4k Workshop Shared architecture diagram](../docs/images/readme-diagrams/shared-diagram-01.png)

## 흐름 다이어그램

1. `shared`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크숍 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 이미지가 있으면 아래 이미지가 상호작용 순서를 보여주며, 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

이 모듈은 Bluetape4k Workshop 예제 전반에서 사용하는 shared utilities를 제공합니다.

대부분의 기능은 이미 `Bluetape4k`가 제공하지만, 워크숍 예제가 추가 유틸리티를 필요로 할 때 이 모듈이 그 빈틈을 채웁니다.

## 핵심 기능

- **RestClientExtensions**: `RestClient` 확장 함수(GET, POST, PUT, PATCH, DELETE)
- **WebClientExtensions**: Reactive `WebClient` 확장 함수
- **WebTestClientExtensions**: `WebTestClient`용 테스트 확장 함수
- **AbstractSpringTest**: Spring 통합 테스트 base class

## 모듈 구조

![shared Architecture diagram](../docs/images/readme-diagrams/shared-diagram-01.png)

## 제공 유틸리티

### RestClientExtensions(동기 HTTP Client)

Spring `RestClient` method chain을 간결한 형태로 감싸는 확장 함수입니다.

| 함수 | HTTP Method | 설명 |
|---|---|---|
| `httpGet(uri, accept?)` | GET | 간단한 조회 |
| `httpHead(uri, accept?)` | HEAD | header-only 조회 |
| `httpPost(uri, value?, ...)` | POST | 단일 객체 전송 |
| `httpPost<T>(uri, publisher, ...)` | POST | Reactor Publisher stream 전송 |
| `httpPost<T>(uri, flow, ...)` | POST | Kotlin Flow stream 전송 |
| `httpPut(uri, value?, ...)` | PUT | 단일 객체 갱신 |
| `httpPatch(uri, value?, ...)` | PATCH | 부분 갱신 |
| `httpDelete(uri, accept?)` | DELETE | 삭제 |

### WebClientExtensions(비동기/Reactive HTTP Client)

Spring `WebClient`에 동일한 signature의 확장 함수를 제공합니다. `Publisher<T>`와 `Flow<T>` overload는 reactive 및 coroutine stream 요청을 단순화합니다.

### WebTestClientExtensions(통합 테스트용)

`httpStatus` 파라미터를 통해 내장 HTTP status assertion을 제공하는 `WebTestClient` 확장 함수입니다. `exchange()` + `expectStatus()` 호출을 한 줄로 줄입니다.

```kotlin
// Usage example
webTestClient.httpGet("/tasks/1", HttpStatus.OK)
    .expectBody<Task>().returnResult()

webTestClient.httpPost("/tasks", task, HttpStatus.CREATED)
```

## 사용법

`build.gradle.kts`에 dependency를 추가합니다.

```kotlin
// For production code
implementation(project(":shared"))

// For test code
testImplementation(project(":shared"))
```

### AbstractSpringTest 확장

Spring WebFlux 통합 테스트 base class를 확장하면 `WebTestClient` 빈이 자동으로 주입됩니다.

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractSpringTest {
    @Autowired
    lateinit var webTestClient: WebTestClient
}

class MyControllerTest : AbstractSpringTest() {
    @Test
    fun `find tasks`() {
        webTestClient.httpGet("/tasks", HttpStatus.OK)
            .expectBodyList<Task>().hasSize(2)
    }
}
```

## 빌드

```bash
./gradlew :shared:build
./gradlew :shared:test
```
