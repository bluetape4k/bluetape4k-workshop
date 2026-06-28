# bluetape4k-workshop

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-ED8B00?logo=openjdk)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![bluetape4k](https://img.shields.io/badge/bluetape4k-1.7-4A90D9)](https://github.com/bluetape4k)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](README.md) | 한국어

[bluetape4k](https://github.com/bluetape4k) 라이브러리를 실무형 Spring Boot 4, Exposed,
Redis, Kafka, 관찰 가능성, 가상 스레드, Vert.x, 클라우드 네이티브 워크로드 맥락에서
직접 돌려보며 익힐 수 있는 백엔드 예제 워크숍입니다.

![Workshop workbench](./docs/assets/workshop-workbench.png)

![Workshop Module Map](./docs/images/readme-charts/root-readme-module-chart-01.png)

![Bluetape4k Workshop Overview](./docs/images/readme-diagrams/root-readme-overview-01.png)

---

## 시작하기

```bash
# 전체 빌드
./gradlew build

# 단일 모듈 빌드 및 테스트
./gradlew :exposed-mvc-jdbc:build
./gradlew :exposed-mvc-jdbc:test

# 정적 분석
./gradlew detekt
```

**요구 사항**: JDK 21+, Docker (Testcontainers)

이 저장소는 실행 가능한 cookbook로 구성됩니다.

1. 지금 해결하려는 백엔드 문제와 가장 가까운 도메인을 고릅니다.
2. 전체 저장소를 빌드하기 전에 하나의 모듈 테스트부터 실행합니다.
3. Testcontainers 또는 다계층 동작이 필요할 때 Basic 모듈에서 Advanced 모듈로 이동합니다.
4. 모듈 README를 읽을 때는 소스 코드 기준으로 판단합니다.

학습을 시작할 때는 모듈 지도를 먼저 보고, 우선 **Basic** 예제로 흐름을 익힌 뒤
필요 시 분산/인프라 동작이 중요한 지점으로 **Advanced** 예제를 확장합니다.

---

## 도메인 카탈로그

모듈은 일곱 개의 학습 도메인으로 구성됩니다.
각 도메인에는 **Basic** (독립적, 최소 인프라)과 **Advanced** (다계층, Testcontainers) 모듈이 있습니다.

![Workshop Module Composition](./docs/images/readme-charts/root-readme-module-chart-01.png)

### 1. 데이터 접근

> Exposed ORM, R2DBC, JPA/QueryDSL, MongoDB, Elasticsearch, Redis

| 수준 | 모듈 | bluetape4k 라이브러리 | 인프라 | 학습 목표 |
|------|------|----------------------|-------|-----------|
| Basic | [`exposed-mvc-jdbc`](exposed/mvc-jdbc/) | `logging`, `junit5`, `testcontainers` | PostgreSQL (TC) | Spring MVC JDBC로 Exposed DAO/SQL DSL 사용 |
| Basic | [`exposed-webflux-r2dbc`](exposed/webflux-r2dbc/) | `logging`, `coroutines`, `testcontainers` | PostgreSQL (TC) | Exposed R2DBC + WebFlux 코루틴 핸들러 |
| Advanced | [`exposed-mvc-virtualthread`](exposed/mvc-virtualthread/) | `logging`, `coroutines`, `testcontainers` | PostgreSQL (TC) | 가상 스레드를 활용한 Exposed JDBC + Spring MVC |
| Basic | [`spring-data-r2dbc-coroutines`](spring-data/r2dbc-coroutines/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | 코루틴을 활용한 R2DBC 레포지토리 |
| Basic | [`spring-data-r2dbc-examples`](spring-data/r2dbc-examples/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | R2DBC 기본 예제와 DSL |
| Advanced | [`spring-data-r2dbc-webflux`](spring-data/r2dbc-webflux/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | R2DBC + WebFlux REST API |
| Advanced | [`spring-data-r2dbc-webflux-exposed`](spring-data/r2dbc-webflux-exposed/) | `logging`, `coroutines`, `testcontainers` | PostgreSQL (TC) | R2DBC + WebFlux + Exposed 조합 |
| Basic | [`spring-data-jpa-querydsl`](spring-data/jpa-querydsl/) | `logging`, `junit5`, `testcontainers` | PostgreSQL (TC) | JPA + QueryDSL 타입 안전 쿼리 |
| Basic | [`spring-data-mongodb-coroutines`](spring-data/mongodb-coroutines/) | `coroutines`, `testcontainers` | MongoDB (TC) | 코루틴을 활용한 MongoDB 리액티브 레포지토리 |
| Advanced | [`spring-data-mongodb-transactions`](spring-data/mongodb-transactions/) | `coroutines`, `testcontainers` | MongoDB (TC) | MongoDB 멀티 도큐먼트 트랜잭션 |
| Basic | [`spring-data-elasticsearch`](spring-data/elasticsearch/) | `logging`, `testcontainers` | Elasticsearch (TC) | Spring Data Elasticsearch 블로킹 방식 |
| Advanced | [`spring-data-elasticsearch-webflux`](spring-data/elasticsearch-webflux/) | `coroutines`, `testcontainers` | Elasticsearch (TC) | Elasticsearch + WebFlux 리액티브 |
| Basic | [`spring-data-redis-examples`](spring-data/redis-examples/) | `redis`, `testcontainers` | Redis (TC) | Spring Data Redis와 RedisTemplate |

```bash
./gradlew :exposed-mvc-jdbc:test
./gradlew :spring-data-r2dbc-coroutines:test
```

---

### 2. Spring Boot 운영

> 캐시, 복원력, WebFlux, WebSocket, CBOR, Protobuf, 카오스 엔지니어링

| 수준 | 모듈 | bluetape4k 라이브러리 | 인프라 | 학습 목표 |
|------|------|----------------------|-------|-----------|
| Basic | [`spring-boot-cache-caffeine`](spring-boot/cache-caffeine/) | `logging`, `junit5` | In-memory | Spring Cache 추상화와 Caffeine 캐시 |
| Basic | [`spring-boot-cache-redis`](spring-boot/cache-redis/) | `redis`, `testcontainers` | Redis (TC) | Redis 기반 Spring Cache + TTL 설정 |
| Basic | [`spring-boot-webflux-coroutines`](spring-boot/webflux-coroutines/) | `coroutines`, `spring-boot4-core` | In-memory | WebFlux 코루틴 핸들러, suspend 컨트롤러 |
| Advanced | [`spring-boot-resilience4j-coroutines`](spring-boot/resilience4j-coroutines/) | `resilience4j`, `coroutines` | In-memory | 코루틴과 함께하는 서킷 브레이커 + 재시도 + 속도 제한 |
| Basic | [`spring-boot-cbor-mvc`](spring-boot/cbor-mvc/) | `logging` | In-memory | Spring MVC에서 CBOR 바이너리 직렬화 |
| Basic | [`spring-boot-protobuf-mvc`](spring-boot/protobuf-mvc/) | `logging` | In-memory | Spring MVC에서 Protobuf 직렬화 |
| Advanced | [`spring-boot-stomp-websocket`](spring-boot/stomp-websocket/) | `coroutines` | In-memory | 코루틴 메시지 핸들러를 활용한 STOMP WebSocket |
| Advanced | [`spring-boot-webflux-websocket`](spring-boot/webflux-websocket/) | `coroutines` | In-memory | WebFlux 리액티브 WebSocket |
| Advanced | [`spring-boot-chaos-monkey`](spring-boot/chaos-monkey/) | `logging` | In-memory | Spring Boot용 Chaos Monkey — 지연/예외 주입 |
| Basic | [`spring-boot-problem`](spring-boot/problem/) | `logging` | In-memory | RFC 9457 Problem Details 에러 응답 |
| Advanced | [`spring-boot-application-event-demo`](spring-boot/application-event-demo/) | `coroutines` | In-memory | 코루틴 리스너를 활용한 Spring 애플리케이션 이벤트 |
| Advanced | [`spring-boot-multi-tenant-data-isolation`](spring-boot/multi-tenant-data-isolation/) | `exposed-jdbc`, `spring-boot4-core`, `micrometer` | H2 | 테넌트별 repository, cache key, lock key, rate-limit, metrics 격리 |

```bash
./gradlew :spring-boot-cache-redis:test
./gradlew :spring-boot-resilience4j-coroutines:test
./gradlew :spring-boot-multi-tenant-data-isolation:test
```

---

### 3. 직렬화 & 메시징

> Jackson 3, JsonView, Kafka, Kafka Reply

| 수준 | 모듈 | bluetape4k 라이브러리 | 인프라 | 학습 목표 |
|------|------|----------------------|-------|-----------|
| Basic | [`jackson-examples`](json/jackson-examples/) | `jackson3`, `logging` | In-memory | Jackson 3 데이터타입, 다형성, 커스텀 직렬화 |
| Basic | [`jsonview-examples`](json/jsonview-examples/) | `jackson3` | In-memory | 선택적 필드 프로젝션을 위한 `@JsonView` |
| Basic | [`messaging-kafka`](messaging/kafka/) | `jackson3`, `coroutines`, `testcontainers` | Kafka (TC) | 코루틴을 활용한 Kafka 프로듀서/컨슈머 |
| Advanced | [`messaging-kafka-reply`](messaging/kafka-reply/) | `jackson3`, `coroutines`, `testcontainers` | Kafka (TC) | `ReplyingKafkaTemplate`을 활용한 Kafka 요청-응답 패턴 |

```bash
./gradlew :jackson-examples:test
./gradlew :messaging-kafka:test
```

---

### 4. 비동기 & 리액티브

> Kotlin 코루틴, 가상 스레드, Vert.x

| 수준 | 모듈 | bluetape4k 라이브러리 | 인프라 | 학습 목표 |
|------|------|----------------------|-------|-----------|
| Basic | [`coroutines`](kotlin/coroutines/) | `coroutines`, `junit5` | In-memory | 코루틴 빌더, Flow, 채널, 구조적 동시성 |
| Basic | [`design-patterns`](kotlin/design-patterns/) | `logging`, `coroutines` | In-memory | Kotlin 비동기 디자인 패턴 |
| Basic | [`flow-extensions-parallel-enrichment`](kotlin/flow-extensions-parallel-enrichment/) | `coroutines`, `junit5` | In-memory | 고객/재고/프로모션 조회를 병렬 Flow로 enrichment |
| Basic | [`flow-extensions-subject-bridge`](kotlin/flow-extensions-subject-bridge/) | `coroutines`, `junit5` | In-memory | bluetape4k Subject 타입으로 callback-to-Flow bridge 구성 |
| Advanced | [`flow-extensions-race-fallback`](kotlin/flow-extensions-race-fallback/) | `coroutines`, `junit5` | In-memory | 여러 source Flow의 race, fallback, eager concat, merge, materialized error 의미론 |
| Basic | [`virtualthreads-rules`](virtualthreads/rules/) | `logging` | In-memory | 가상 스레드 피닝 방지, synchronized 회피 |
| Advanced | [`virtualthreads-spring-mvc-tomcat`](virtualthreads/spring-mvc-tomcat/) | `logging`, `testcontainers` | PostgreSQL (TC) | 가상 스레드 실행기를 활용한 Spring MVC |
| Advanced | [`virtualthreads-spring-webflux`](virtualthreads/spring-webflux/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | 가상 스레드 디스패처를 활용한 WebFlux |
| Basic | [`vertx-coroutines`](vertx/coroutines/) | `coroutines` | In-memory | Vert.x 코루틴 어댑터 |
| Advanced | [`vertx-sqlclient`](vertx/vertx-sqlclient/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | Vert.x Reactive SQL Client + 코루틴 |
| Advanced | [`vertx-webclient`](vertx/vertx-webclient/) | `coroutines` | WireMock | 코루틴을 활용한 Vert.x WebClient |

```bash
./gradlew :coroutines:test
./gradlew :virtualthreads-spring-mvc-tomcat:test
```

---

### 5. 관찰 가능성 & 성능

> Micrometer Observation/Tracing, Gatling 부하 테스트

| 수준 | 모듈 | bluetape4k 라이브러리 | 인프라 | 학습 목표 |
|------|------|----------------------|-------|-----------|
| Basic | [`observability-basic`](observability/observability-basic/) | `micrometer`, `coroutines`, `jackson3` | MockWebServer | 코루틴 스팬 전파, `TestObservationRegistry` |
| Advanced | [`observability-advanced`](observability/observability-advanced/) | `micrometer`, `coroutines`, `testcontainers` | PostgreSQL + Redis (TC) | HTTP, DB, Redis 계층 전반의 엔드투엔드 추적 |
| Basic | [`micrometer-observation`](observability/micrometer-observation/) | `micrometer`, `logging` | In-memory | Micrometer Observation API 기초 |
| Advanced | [`micrometer-tracing-coroutines`](observability/micrometer-tracing-coroutines/) | `micrometer`, `coroutines` | In-memory | 코루틴 경계를 통한 분산 추적 컨텍스트 |
| Advanced | [`gatling-virtualthread-simulation`](gatling/virtualthread-simulation/) | `junit5` | App process | 가상 스레드 서버를 대상으로 한 Gatling 부하 시뮬레이션 |

```bash
./gradlew :observability-basic:test
./gradlew :gatling-virtualthread-simulation:gatlingRun
```

---

### 6. Graph

> TinkerGraph 예제, graph traversal, graph-io import/export

| 수준 | 모듈 | bluetape4k 라이브러리 | 인프라 | 학습 목표 |
|------|------|----------------------|-------|-----------|
| Basic | [`graph-io-pipeline`](graph/io-pipeline/) | `graph-core`, `graph-tinkerpop`, `graph-io-csv`, `graph-io-jackson3`, `graph-io-graphml` | In-memory | CSV fixture import, Jackson3 NDJSON export/import, GraphML export/import, graph-io report 확인 |
| Basic | [`graph-social-network`](graph/social-network/) | `graph-core`, `graph-tinkerpop` | In-memory | Social graph modeling과 traversal |
| Basic | [`graph-knowledge-graph`](graph/knowledge-graph/) | `graph-core`, `graph-tinkerpop` | In-memory | Heterogeneous knowledge graph model과 traversal |
| Advanced | [`graph-abuser-detection`](graph/abuser-detection/) | `graph-core`, `graph-tinkerpop` | In-memory | Fraud/abuse 관계 분석 |
| Advanced | [`graph-recommendation`](graph/recommendation/) | `graph-core`, `graph-tinkerpop` | In-memory | Recommendation graph traversal |

```bash
./gradlew :graph-io-pipeline:test
./gradlew :graph-social-network:test
```

---

### 7. 아키텍처 확장

> API Gateway, Spring Modulith, 보안, Redis 분산 패턴, AWS, Rate Limiting, 리더 선출

| 수준 | 모듈 | bluetape4k 라이브러리 | 인프라 | 학습 목표 |
|------|------|----------------------|-------|-----------|
| Basic | [`spring-security-mvc`](spring-security/mvc/) | `logging`, `spring-boot4-core` | In-memory | Spring Security MVC: JWT, 메서드 보안 |
| Basic | [`spring-security-webflux`](spring-security/webflux/) | `coroutines`, `spring-boot4-core` | In-memory | Spring Security WebFlux: 리액티브 필터 체인 |
| Advanced | [`spring-modulith-events-deep-dive`](spring-modulith/events-deep-dive/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | 영속성을 갖춘 Modulith 애플리케이션 이벤트 |
| Advanced | [`spring-modulith-jpa-demo`](spring-modulith/jpa-demo/) | `logging`, `testcontainers` | PostgreSQL (TC) | JPA를 활용한 Modulith 모듈 캡슐화 |
| Advanced | [`gateway-api-gateway`](gateway/api-gateway/) | `logging` | Services running | Spring Cloud Gateway 라우팅, 필터, 프레디케이트 |
| Basic | [`ratelimit-bucket4j-caffeine-web`](ratelimit/bucket4j-caffeine-web/) | `logging` | In-memory | Caffeine을 활용한 Bucket4j 속도 제한 |
| Advanced | [`ratelimit-bucket4j-redis`](ratelimit/bucket4j-redis/) | `redis`, `testcontainers` | Redis (TC) | Bucket4j + Redis를 통한 분산 속도 제한 |
| Advanced | [`ratelimit-bucker4j-bluetape4k-webflux`](ratelimit/bucker4j-bluetape4k-webflux/) | `redis`, `coroutines`, `testcontainers` | Redis (TC) | bluetape4k WebFlux 속도 제한 통합 |
| Advanced | [`redis-distributed-lock`](redis/distributed-lock/) | `redis`, `redisson`, `coroutines` | Redis (TC) | Redisson + 코루틴 suspend를 활용한 분산 락 |
| Advanced | [`redis-cluster-demo`](redis/cluster-demo/) | `redis`, `redisson` | Redis Cluster (TC) | Redis 클러스터 토폴로지 및 장애 조치 |
| Basic | [`redis-redisson-examples`](redis/redisson-examples/) | `redis`, `redisson` | Redis (TC) | Redisson 데이터 구조와 Pub/Sub |
| Advanced | [`aws-s3-spring-cloud`](aws/s3-spring-cloud/) | `aws`, `testcontainers` | LocalStack (TC) | Spring Cloud AWS + LocalStack으로 AWS S3 사용 |
| Advanced | [`image-processing-advanced-workflow`](image-processing/advanced-workflow/) | `images-vips-java25`, `images-spring-boot`, `micrometer` | S3 또는 local storage | 업로드 → 원본 저장 → WebP 파생 이미지 → unsigned public URL |
| Advanced | [`image-processing-ocr-api`](image-processing/ocr-api/) | `images-ocr`, `images`, `spring-boot4-core` | In-memory | 검증된 fallback과 선택 Tesseract를 사용하는 multipart OCR API |
| Advanced | [`leader-leader-election`](leader/) | `coroutines`, `redis`, `testcontainers` | Redis (TC) | 분산 리더 선출: 블로킹, 코루틴, 가상 스레드 |
| Advanced | [`leader-k8s-lease-micrometer`](leader/k8s-lease-micrometer/) | `leader-k8s`, `micrometer`, `spring-boot4-core` | Kubernetes Lease (opt-in) | Micrometer metric과 함께 배우는 Kubernetes Lease 리더 선출 |

```bash
./gradlew :spring-security-mvc:test
./gradlew :redis-distributed-lock:test
./gradlew :aws-s3-spring-cloud:test
```

---

## 기술 스택

| 항목 | 버전 |
|------|------|
| Kotlin | 2.3.21 |
| JVM | 21 |
| Spring Boot | 4.0.6 |
| bluetape4k | 1.7.0 |
| Gradle | 8.x (Kotlin DSL, 멀티모듈) |

---

## 프로젝트 구조

```
bluetape4k-workshop/
├── aws/                    # AWS SDK + Spring Cloud AWS
├── exposed/                # JetBrains Exposed ORM
├── gateway/                # API Gateway + 마이크로서비스
├── gatling/                # 부하/성능 테스트
├── graph/                  # TinkerGraph, traversal, graph-io 예제
├── image-processing/       # 이미지 업로드, VIPS 파생 이미지, OCR API
├── io/                     # Okio I/O 예제
├── json/                   # Jackson 3 직렬화
├── kotlin/                 # 코루틴, 디자인 패턴
├── leader/                 # 분산 리더 선출
├── messaging/              # Kafka
├── observability/          # Micrometer Observation + Tracing
├── ratelimit/              # Bucket4j 속도 제한
├── redis/                  # Redisson + Redis 패턴
├── spring-boot/            # Spring Boot 기능
├── spring-data/            # JPA, R2DBC, MongoDB, Elasticsearch
├── spring-modulith/        # Spring Modulith 이벤트
├── spring-security/        # MVC/WebFlux 보안
├── vertx/                  # Vert.x + 코루틴
├── virtualthreads/         # 가상 스레드 패턴
├── shared/                 # 공유 테스트 유틸리티
└── docs/
    ├── assets/             # 다이어그램, 이미지 (CONVENTIONS.md 참조)
    ├── lessons/            # 엔지니어링 교훈
    └── governance/         # 프로젝트 거버넌스 문서
```

---

## 기여하기

AI 에이전트 가이드 및 엔지니어링 워크플로우는 [AGENTS.md](AGENTS.md)를 참조하세요.
인간 기여자는 이슈 또는 드래프트 PR을 열어주세요 — 모든 피드백을 환영합니다.

---

## 라이선스

[MIT](LICENSE) © bluetape4k contributors
