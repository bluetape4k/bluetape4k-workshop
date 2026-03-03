# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`bluetape4k-workshop`는 [Bluetape4k](https://github.com/bluetape4k/bluetape4k) 라이브러리를 활용한 백엔드 애플리케이션 예제 모음입니다. Kotlin 2.3 + Java 25 + Spring Boot 4 기반의 Gradle 멀티모듈 프로젝트입니다.

## Build & Test Commands

```bash
# 전체 빌드
./gradlew build

# 특정 모듈 빌드
./gradlew :exposed-domain:build

# 전체 테스트
./gradlew test

# 특정 모듈 테스트
./gradlew :exposed-domain:test

# 특정 테스트 클래스 실행
./gradlew :exposed-domain:test --tests "io.bluetape4k.workshop.exposed.domain.SomeTest"

# 특정 테스트 메서드 실행
./gradlew :exposed-domain:test --tests "io.bluetape4k.workshop.exposed.domain.SomeTest.testMethod"

# 정적 분석 (Detekt)
./gradlew detekt

# 클린 빌드
./gradlew clean build
```

## Module Structure

`settings.gradle.kts`의 `includeModules()` 함수가 각 도메인 디렉토리 하위 서브모듈을 자동으로 등록합니다. 모듈 이름 패턴은 `{domain}-{submodule}`입니다.

주요 도메인 디렉토리:

| 디렉토리 | 모듈 예시 | 내용 |
|---|---|---|
| `exposed/` | `exposed-domain`, `exposed-spring-transaction` | JetBrains Exposed ORM 예제 |
| `spring-boot/` | `webflux-coroutines`, `cache-redis`, `resilience4j-coroutines` | Spring Boot 기능 예제 |
| `spring-data/` | `r2dbc-webflux-exposed`, `jpa-querydsl`, `mongodb-coroutines` | Spring Data 예제 |
| `spring-cloud/` | `gateway-example` | Spring Cloud Gateway |
| `spring-modulith/` | `events-deep-dive`, `jpa-demo` | Spring Modulith |
| `spring-security/` | `mvc`, `webflux` | 보안 예제 |
| `kotlin/` | `coroutines`, `design-patterns` | Kotlin 언어 기능 |
| `messaging/` | `kafka`, `kafka-reply` | Kafka 메시징 |
| `redis/` | `redisson-examples`, `cluster-demo` | Redis 예제 |
| `virtualthreads/` | `spring-mvc-tomcat`, `spring-webflux` | Virtual Threads |
| `observability/` | `micrometer-observation`, `micrometer-tracing-coroutines` | 관찰가능성 |
| `shared/` | — | 테스트 공통 유틸리티 |

## Architecture & Key Conventions

### 의존성 버전 관리
모든 버전은 `buildSrc/src/main/kotlin/Libs.kt`의 `Libs`, `Versions`, `Plugins` 객체에서 중앙 관리합니다. 새 의존성 추가 시 반드시 이 파일에 먼저 정의하세요.

### 루트 `build.gradle.kts` 전역 설정
- **Java 25 + Kotlin 2.3** toolchain 적용
- 실험적 Kotlin 기능 opt-in 전역 활성화: `ExperimentalCoroutinesApi`, `FlowPreview`, `ExperimentalContracts` 등
- **테스트 직렬화**: `TestMutexService` (maxParallelUsages=1)로 멀티모듈 테스트를 순차 실행 (DB 충돌 방지)
- 테스트 JVM 설정: `-XX:+UseZGC`, `-Xms2G`, `-Xmx4G`, `--enable-preview`
- BOM 관리: Spring Boot 4, Kotlin Coroutines, Jackson, Testcontainers, JUnit 등

### 패키지 구조
```
io.bluetape4k.workshop.{module}.*
```

### 테스트 패턴
- JUnit 5 (`useJUnitPlatform()`)
- Kluent assertion 라이브러리 (`shouldBeEqualTo`, `shouldNotBeNull` 등)
- MockK 목킹 프레임워크
- Testcontainers로 실제 DB(MariaDB, MySQL, PostgreSQL, CockroachDB) 통합 테스트
- `AbstractExposedTest`, `ContainerProvider` 등 공통 베이스 클래스는 `exposed/domain/src/test`에 위치

### Exposed 모듈 패턴
- `exposed-domain`: 순수 Exposed ORM 매핑 예제 (DAO/SQL DSL, 연관관계, 커스텀 컬럼 등)
- `exposed-spring-transaction`: Spring 트랜잭션 연동
- `r2dbc-webflux-exposed`: R2DBC + WebFlux + Exposed 조합

### Spring Boot 모듈 구조
Spring Boot 모듈은 `springBoot { mainClass.set(...) }`를 명시하며, `configurations { testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get()) }` 패턴으로 테스트 의존성을 확장합니다.

### Bluetape4k 라이브러리 활용
핵심 의존성으로 `bluetape4k-*` 모듈을 사용합니다:
- `bluetape4k-logging`: KotlinLogging 기반 로거
- `bluetape4k-junit5`: 테스트 확장 유틸리티
- `bluetape4k-coroutines`: 코루틴 유틸리티
- `bluetape4k-exposed`: Exposed 확장
- `bluetape4k-testcontainers`: Testcontainers 헬퍼