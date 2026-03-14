# CLAUDE.md

Bluetape4k 라이브러리 활용 백엔드 예제 모음. **Kotlin 2.3 + Java 25 + Spring Boot 4**, Gradle 멀티모듈.

## Build & Test

```bash
./gradlew build                          # 전체 빌드
./gradlew :exposed-domain:build          # 특정 모듈 빌드
./gradlew :exposed-domain:test           # 특정 모듈 테스트
./gradlew :exposed-domain:test --tests "io.bluetape4k.workshop.exposed.domain.SomeTest.testMethod"
./gradlew detekt                         # 정적 분석
./gradlew clean build                    # 클린 빌드
```

## 모듈 구조

`settings.gradle.kts`의 `includeModules()`가 서브모듈 자동 등록. 패턴: `{domain}-{submodule}`

| 디렉토리               | 내용                                                   |
|--------------------|------------------------------------------------------|
| `exposed/`         | JetBrains Exposed ORM 예제 (DAO/SQL DSL, 연관관계, 커스텀 컬럼) |
| `spring-boot/`     | WebFlux, Cache, Resilience4j 등 Spring Boot 기능 예제     |
| `spring-data/`     | R2DBC, JPA/QueryDSL, MongoDB 예제                      |
| `spring-cloud/`    | Gateway                                              |
| `spring-modulith/` | Events, JPA 데모                                       |
| `spring-security/` | MVC/WebFlux 보안 예제                                    |
| `kotlin/`          | 코루틴, 디자인 패턴                                          |
| `messaging/`       | Kafka                                                |
| `redis/`           | Redisson, 클러스터                                       |
| `virtualthreads/`  | Virtual Threads + MVC/WebFlux                        |
| `observability/`   | Micrometer Observation/Tracing                       |
| `shared/`          | 테스트 공통 유틸리티                                          |

## 핵심 규칙

- **버전 관리**: `buildSrc/src/main/kotlin/Libs.kt` — 새 의존성은 여기 먼저 정의
- **패키지**: `io.bluetape4k.workshop.{module}.*`
- **테스트**: JUnit 5 + Kluent + MockK + Testcontainers (MariaDB/MySQL/PostgreSQL/CockroachDB)
    - 공통 베이스: `exposed/domain/src/test` (`AbstractExposedTest`, `ContainerProvider`)
    - 직렬 실행: `TestMutexService` (maxParallelUsages=1) — DB 충돌 방지
- **JVM**: G1GC, `-Xms2G -Xmx4G`, `--enable-preview`
- **Spring Boot 모듈**: `springBoot { mainClass.set(...) }` + `testImplementation.extendsFrom(compileOnly, runtimeOnly)`

## 주요 bluetape4k 모듈

`bluetape4k-logging`, `bluetape4k-junit5`, `bluetape4k-coroutines`, `bluetape4k-exposed`, `bluetape4k-testcontainers`
