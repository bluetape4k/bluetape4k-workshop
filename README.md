# bluetape4k-workshop

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-ED8B00?logo=openjdk)](https://openjdk.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

![Bluetape4k workshop workbench](./docs/assets/workshop-workbench.png)

Bluetape4k 라이브러리를 활용한 백엔드 예제 모음입니다.

## Project Purpose

`bluetape4k-workshop` collects runnable backend examples that show how stable
bluetape4k libraries fit into Spring Boot, Exposed, Redis, Kafka, security,
observability, virtual threads, Vert.x, and gateway-style applications.

## What It Provides

- **Runnable module groups** for common backend stacks.
- **Integration examples** for bluetape4k data, infra, I/O, and testing modules.
- **Test-first learning material** with Testcontainers and shared utilities.
- **Adoption bridge** from isolated library APIs to application-shaped examples.

## 기술 스택

| 항목          | 버전               |
|-------------|------------------|
| Kotlin      | 2.3.21           |
| Java        | 21               |
| Spring Boot | 4.0.6            |
| bluetape4k  | 1.7.0            |
| Gradle      | Kotlin DSL, 멀티모듈 |

## 빌드

```bash
./gradlew build                          # 전체 빌드
./gradlew :exposed-domain:build          # 특정 모듈 빌드
./gradlew :exposed-domain:test           # 특정 모듈 테스트
./gradlew detekt                         # 정적 분석
```

`exposed-domain` 테스트는 `io.bluetape4k.exposed.jdbc.selectImplicitAll` 같은
main JDBC helper extension을 직접 검증합니다. `bluetape4k-exposed-jdbc-tests`는
test fixture 계약만 제공하므로, 해당 테스트 모듈에는 `bluetape4k-exposed-jdbc`
test dependency도 함께 선언해야 합니다.

## 전체 모듈 구성

```mermaid
flowchart LR
    subgraph Core["핵심 모듈"]
        EX["exposed/"]
        SB["spring-boot/"]
        SD["spring-data/"]
    end
    subgraph Infra["인프라/메시징"]
        MSG["messaging/"]
        RD["redis/"]
        GW["gateway/"]
    end
    subgraph Obs["관찰 가능성"]
        OB["observability/"]
        RL["ratelimit/"]
    end
    subgraph Alt["대안 기술"]
        VX["vertx/"]
        QK["quarkus/"]
        RE["reactive/"]
    end
    SH["shared/"] --> Core & Infra
```

## 모듈 구조

| 디렉토리               | 내용                                                |
|--------------------|---------------------------------------------------|
| `aws/`             | S3 Spring Cloud 연동                                |
| `exposed/`         | JetBrains Exposed ORM (DAO/SQL DSL, 연관관계, 커스텀 컬럼) |
| `gateway/`         | API Gateway + Customers/Orders 마이크로서비스            |
| `gatling/`         | Gatling 성능 테스트                                    |
| `io/`              | Okio 예제                                           |
| `json/`            | Jackson, JsonView 예제                              |
| `kotlin/`          | 코루틴, 디자인 패턴, 워크숍                                  |
| `mapping/`         | MapStruct 매핑                                      |
| `messaging/`       | Kafka, Kafka Reply                                |
| `observability/`   | Micrometer Observation/Tracing                    |
| `quarkus/`         | Hibernate Reactive Panache, REST 코루틴 *(빌드 비활성)*   |
| `ratelimit/`       | Bucket4j Rate Limiting (Caffeine, Redis, WebFlux) |
| `reactive/`        | Mutiny 리액티브 스트림                                   |
| `redis/`           | Redisson, 클러스터                                    |
| `spring-boot/`     | WebFlux, Cache, Resilience4j 등 Spring Boot 기능 예제  |
| `spring-cloud/`    | Gateway *(빌드 비활성 — Spring Boot 4 호환 대기)*          |
| `spring-data/`     | R2DBC, JPA/QueryDSL, MongoDB, Elasticsearch       |
| `spring-modulith/` | Events, JPA 데모                                    |
| `spring-security/` | MVC/WebFlux 보안 예제                                 |
| `vertx/`           | Vert.x 코루틴, SQL Client, WebClient                 |
| `virtualthreads/`  | Virtual Threads + MVC/WebFlux                     |
| `shared/`          | 테스트 공통 유틸리티                                       |

## 테스트

- JUnit 5 + bluetape4k-assertions + MockK
- Testcontainers (MariaDB, MySQL, PostgreSQL, CockroachDB)
- JVM: ZGC, `-Xms2G -Xmx4G`, `--enable-preview`
