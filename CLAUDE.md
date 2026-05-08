# CLAUDE.md — bluetape4k-workshop

Bluetape4k 라이브러리 활용 백엔드 예제 모음.
**Kotlin 2.3.20 + Java 25 + Spring Boot 4.0.3**, bluetape4k **1.5.0-Beta2**.

## Build & Test

```bash
./gradlew build
./gradlew :exposed-domain:build
./gradlew :exposed-domain:test
./gradlew :exposed-domain:test --tests "io.bluetape4k.workshop.exposed.domain.SomeTest.testMethod"
./gradlew detekt
./gradlew clean build
```

## 모듈 구조

`settings.gradle.kts` 의 `includeModules()` 가 서브모듈 자동 등록. 패턴: `{domain}-{submodule}`

| 디렉토리 | 내용 |
|--------|------|
| `aws/` | S3 Spring Cloud 연동 |
| `exposed/` | JetBrains Exposed ORM 예제 (DAO/SQL DSL, 연관관계, 커스텀 컬럼, Spring Transaction 통합) |
| `gateway/` | API Gateway + Customers/Orders 마이크로서비스 |
| `gatling/` | Gatling 성능 테스트 (Gradle 플러그인, Virtual Thread 시뮬레이션) |
| `io/` | Okio 예제 |
| `json/` | Jackson 3.x, JsonView 예제 |
| `kotlin/` | 코루틴, 디자인 패턴, 워크샵 |
| `mapping/` | MapStruct 매핑 |
| `messaging/` | Kafka, Kafka Reply |
| `observability/` | Micrometer Observation/Tracing (코루틴 포함) |
| `ratelimit/` | Bucket4j 기반 Rate Limiting (Caffeine, Redis, WebFlux) |
| `reactive/` | Mutiny 리액티브 스트림 |
| `redis/` | Redisson, 클러스터 |
| `spring-boot/` | WebFlux, Cache, Resilience4j 등 Spring Boot 기능 예제 |
| `spring-data/` | R2DBC, JPA/QueryDSL, MongoDB, Elasticsearch |
| `spring-modulith/` | Events, JPA 데모 |
| `spring-security/` | MVC/WebFlux 보안 예제 |
| `vertx/` | Vert.x 코루틴, SQL Client, WebClient |
| `virtualthreads/` | Virtual Threads + MVC/WebFlux |
| `shared/` | 테스트 공통 유틸리티 |

## 핵심 설정

- **버전 관리**: `buildSrc/src/main/kotlin/Libs.kt` — 새 의존성은 여기 먼저 정의
- **패키지**: `io.bluetape4k.workshop.{module}.*`
- **테스트**: 직렬 실행 — `TestMutexService` (`maxParallelUsages=1`) DB 충돌 방지
- **JVM**: ZGC, `-Xms2G -Xmx4G`, `--enable-preview`
- **Spring Boot 모듈**: `springBoot { mainClass.set(...) }` + `testImplementation.extendsFrom(compileOnly, runtimeOnly)`

## 주요 bluetape4k 모듈

`bluetape4k-logging`, `bluetape4k-junit5`, `bluetape4k-coroutines`, `bluetape4k-exposed`, `bluetape4k-testcontainers`
