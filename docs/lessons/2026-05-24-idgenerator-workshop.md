# 2026-05-24 idgenerator Workshop 예제 추가 (Issue #62)

## 작업 개요

`spring-boot/idgenerator` 모듈을 신규 생성했다.
bluetape4k-idgenerators의 4가지 알고리즘(Snowflake, ULID, KSUID, Hashids)을 Spring Boot WebFlux REST API로 노출한다.

## 구현 결정

### 범위 선정

issue #62의 7개 예제 후보 중 Basic 레벨에 맞게 REST API + 역파싱을 우선 구현했다.

| 후보 | 이번 구현 여부 | 이유 |
|---|---|---|
| Snowflake REST API | ✅ | 핵심 — 역파싱 포함 |
| ULID/KSUID REST API | ✅ | 단순 구현 |
| Hashids 인코딩/디코딩 | ✅ | 단순 구현 |
| Redis 기반 worker-id allocation | ❌ | 별도 인프라 필요, 후속 작업 |
| Kafka event ID generation | ❌ | messaging 모듈과 연관 |
| OpenTelemetry tracing | ❌ | observability 모듈과 연관 |
| 벤치마크 | ❌ | gatling 모듈 활용 권장 |

### Snowflakers.Default vs 명시적 machineId

`Snowflakers.Default`는 MAC 기반으로 머신ID를 자동 결정한다.
단일 NIC 환경에서는 편리하지만, Pod/Container 환경에서는 네트워크 인터페이스가 동적으로 변하므로
`Snowflakers.default(machineId = N)` 명시적 지정이 안전하다.
README에 운영 주의사항으로 문서화했다.

### nextIds() 반환 타입

`Snowflake.nextIds(size: Int)` 는 `Sequence<Long>`을 반환한다 — `List<Long>` 아님.
컨트롤러에서 `.map { }.toList()` 호출이 필수다.

### WebTestClient 설정 — Spring Boot 4 패턴

Spring Boot 4 WebFlux 통합 테스트에서 `@AutoConfigureWebTestClient` 대신:
```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@EnableWebFlux
abstract class AbstractTest {
    @Autowired
    protected val context: ApplicationContext = uninitialized()

    protected val client: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).configureClient().build()
    }
}
```
패턴이 정상 동작한다. `@AutoConfigureWebTestClient`는 Spring Boot 4.x에서 패키지 구조 변경으로 `NoClassDefFoundError`가 발생할 수 있다.

## 테스트 결과

```
:spring-boot-idgenerator:test  BUILD SUCCESSFUL
Tests: 10 passing
```

## Hashids 운영 주의사항

Hashids는 암호화가 아닌 난독화 알고리즘이다.
보안 목적 ID에 절대 사용 금지 — salt 설정 필수:
```kotlin
val hashids = Hashids(salt = "프로젝트-고유-secret", minHashLength = 8)
```

## 향후 작업 (issue #62 연계)

- Redis 기반 worker-id 동적 할당: Redisson `RAtomicLong` incrementAndGet + TTL 패턴
- Kafka outbox event ID 생성 예제
- Exposed DB 저장 E2E 흐름
