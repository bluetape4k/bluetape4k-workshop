# bluetape4k-workshop

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-ED8B00?logo=openjdk)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![bluetape4k](https://img.shields.io/badge/bluetape4k-1.7-4A90D9)](https://github.com/bluetape4k)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> 🇰🇷 [한국어 README](README.ko.md)

Runnable backend examples showing how [bluetape4k](https://github.com/bluetape4k) libraries
integrate with Spring Boot 4, JetBrains Exposed, Redis, Kafka, observability stacks,
virtual threads, Vert.x, and cloud-native patterns.

![Workshop workbench](./docs/assets/workshop-workbench.png)

---

## Getting Started

```bash
# Build everything
./gradlew build

# Build and test a single module
./gradlew :exposed-mvc-jdbc:build
./gradlew :exposed-mvc-jdbc:test

# Static analysis
./gradlew detekt
```

**Requirements**: JDK 21+, Docker (Testcontainers)

---

## Domain Catalog

Modules are organized into six learning domains.
Each domain lists **Basic** (self-contained, minimal infra) and **Advanced** (multi-layer, Testcontainers) modules.

### 1. Data Access

> Exposed ORM, R2DBC, JPA/QueryDSL, MongoDB, Elasticsearch, Redis

| Level | Module | bluetape4k libs | Infra | Learning outcome |
|-------|--------|-----------------|-------|-----------------|
| Basic | [`exposed-mvc-jdbc`](exposed/mvc-jdbc/) | `logging`, `junit5`, `testcontainers` | PostgreSQL (TC) | Exposed DAO/SQL DSL with Spring MVC JDBC |
| Basic | [`exposed-webflux-r2dbc`](exposed/webflux-r2dbc/) | `logging`, `coroutines`, `testcontainers` | PostgreSQL (TC) | Exposed R2DBC + WebFlux coroutine handlers |
| Advanced | [`exposed-mvc-virtualthread`](exposed/mvc-virtualthread/) | `logging`, `coroutines`, `testcontainers` | PostgreSQL (TC) | Exposed JDBC with Spring MVC virtual threads |
| Basic | [`spring-data-r2dbc-coroutines`](spring-data/r2dbc-coroutines/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | R2DBC repositories with coroutines |
| Basic | [`spring-data-r2dbc-examples`](spring-data/r2dbc-examples/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | R2DBC raw examples and DSL |
| Advanced | [`spring-data-r2dbc-webflux`](spring-data/r2dbc-webflux/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | R2DBC + WebFlux REST API |
| Advanced | [`spring-data-r2dbc-webflux-exposed`](spring-data/r2dbc-webflux-exposed/) | `logging`, `coroutines`, `testcontainers` | PostgreSQL (TC) | R2DBC + WebFlux + Exposed combined |
| Basic | [`spring-data-jpa-querydsl`](spring-data/jpa-querydsl/) | `logging`, `junit5`, `testcontainers` | PostgreSQL (TC) | JPA + QueryDSL type-safe queries |
| Basic | [`spring-data-mongodb-coroutines`](spring-data/mongodb-coroutines/) | `coroutines`, `testcontainers` | MongoDB (TC) | MongoDB reactive repositories with coroutines |
| Advanced | [`spring-data-mongodb-transactions`](spring-data/mongodb-transactions/) | `coroutines`, `testcontainers` | MongoDB (TC) | MongoDB multi-document transactions |
| Basic | [`spring-data-elasticsearch`](spring-data/elasticsearch/) | `logging`, `testcontainers` | Elasticsearch (TC) | Spring Data Elasticsearch blocking |
| Advanced | [`spring-data-elasticsearch-webflux`](spring-data/elasticsearch-webflux/) | `coroutines`, `testcontainers` | Elasticsearch (TC) | Elasticsearch + WebFlux reactive |
| Basic | [`spring-data-redis-examples`](spring-data/redis-examples/) | `redis`, `testcontainers` | Redis (TC) | Spring Data Redis and RedisTemplate |

```bash
./gradlew :exposed-mvc-jdbc:test
./gradlew :spring-data-r2dbc-coroutines:test
```

---

### 2. Spring Boot Operations

> Caching, resilience, WebFlux, WebSocket, CBOR, Protobuf, chaos engineering

| Level | Module | bluetape4k libs | Infra | Learning outcome |
|-------|--------|-----------------|-------|-----------------|
| Basic | [`spring-boot-cache-caffeine`](spring-boot/cache-caffeine/) | `logging`, `junit5` | In-memory | Caffeine cache with Spring Cache abstraction |
| Basic | [`spring-boot-cache-redis`](spring-boot/cache-redis/) | `redis`, `testcontainers` | Redis (TC) | Redis-backed Spring Cache + TTL config |
| Basic | [`spring-boot-webflux-coroutines`](spring-boot/webflux-coroutines/) | `coroutines`, `spring-boot4-core` | In-memory | WebFlux coroutine handlers, suspend controllers |
| Advanced | [`spring-boot-resilience4j-coroutines`](spring-boot/resilience4j-coroutines/) | `resilience4j`, `coroutines` | In-memory | Circuit breaker + retry + rate limiter with coroutines |
| Basic | [`spring-boot-cbor-mvc`](spring-boot/cbor-mvc/) | `logging` | In-memory | CBOR binary serialization in Spring MVC |
| Basic | [`spring-boot-protobuf-mvc`](spring-boot/protobuf-mvc/) | `logging` | In-memory | Protobuf serialization in Spring MVC |
| Advanced | [`spring-boot-stomp-websocket`](spring-boot/stomp-websocket/) | `coroutines` | In-memory | STOMP WebSocket with coroutine message handlers |
| Advanced | [`spring-boot-webflux-websocket`](spring-boot/webflux-websocket/) | `coroutines` | In-memory | WebFlux reactive WebSocket |
| Advanced | [`spring-boot-chaos-monkey`](spring-boot/chaos-monkey/) | `logging` | In-memory | Chaos Monkey for Spring Boot — latency/exception injection |
| Basic | [`spring-boot-problem`](spring-boot/problem/) | `logging` | In-memory | RFC 9457 Problem Details error responses |
| Advanced | [`spring-boot-application-event-demo`](spring-boot/application-event-demo/) | `coroutines` | In-memory | Spring application events with coroutine listeners |

```bash
./gradlew :spring-boot-cache-redis:test
./gradlew :spring-boot-resilience4j-coroutines:test
```

---

### 3. Serialization & Messaging

> Jackson 3, JsonView, Kafka, Kafka Reply

| Level | Module | bluetape4k libs | Infra | Learning outcome |
|-------|--------|-----------------|-------|-----------------|
| Basic | [`jackson-examples`](json/jackson-examples/) | `jackson3`, `logging` | In-memory | Jackson 3 datatype, polymorphism, custom serializers |
| Basic | [`jsonview-examples`](json/jsonview-examples/) | `jackson3` | In-memory | `@JsonView` for selective field projection |
| Basic | [`messaging-kafka`](messaging/kafka/) | `jackson3`, `coroutines`, `testcontainers` | Kafka (TC) | Kafka producer/consumer with coroutines |
| Advanced | [`messaging-kafka-reply`](messaging/kafka-reply/) | `jackson3`, `coroutines`, `testcontainers` | Kafka (TC) | Kafka request-reply pattern with `ReplyingKafkaTemplate` |

```bash
./gradlew :jackson-examples:test
./gradlew :messaging-kafka:test
```

---

### 4. Async & Reactive

> Kotlin coroutines, virtual threads, Vert.x

| Level | Module | bluetape4k libs | Infra | Learning outcome |
|-------|--------|-----------------|-------|-----------------|
| Basic | [`coroutines`](kotlin/coroutines/) | `coroutines`, `junit5` | In-memory | Coroutine builders, Flow, channels, structured concurrency |
| Basic | [`design-patterns`](kotlin/design-patterns/) | `logging`, `coroutines` | In-memory | Async design patterns in Kotlin |
| Basic | [`virtualthreads-rules`](virtualthreads/rules/) | `logging` | In-memory | Virtual thread pinning, synchronized avoidance |
| Advanced | [`virtualthreads-spring-mvc-tomcat`](virtualthreads/spring-mvc-tomcat/) | `logging`, `testcontainers` | PostgreSQL (TC) | Spring MVC with virtual thread executor |
| Advanced | [`virtualthreads-spring-webflux`](virtualthreads/spring-webflux/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | WebFlux with virtual thread dispatcher |
| Basic | [`vertx-coroutines`](vertx/coroutines/) | `coroutines` | In-memory | Vert.x coroutine adapters |
| Advanced | [`vertx-sqlclient`](vertx/vertx-sqlclient/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | Vert.x Reactive SQL Client + coroutines |
| Advanced | [`vertx-webclient`](vertx/vertx-webclient/) | `coroutines` | WireMock | Vert.x WebClient with coroutines |

```bash
./gradlew :coroutines:test
./gradlew :virtualthreads-spring-mvc-tomcat:test
```

---

### 5. Observability & Performance

> Micrometer Observation/Tracing, Gatling load tests

| Level | Module | bluetape4k libs | Infra | Learning outcome |
|-------|--------|-----------------|-------|-----------------|
| Basic | [`observability-basic`](observability/observability-basic/) | `micrometer`, `coroutines`, `jackson3` | MockWebServer | Coroutine span propagation, `TestObservationRegistry` |
| Advanced | [`observability-advanced`](observability/observability-advanced/) | `micrometer`, `coroutines`, `testcontainers` | PostgreSQL + Redis (TC) | End-to-end tracing across HTTP, DB, and Redis layers |
| Basic | [`micrometer-observation`](observability/micrometer-observation/) | `micrometer`, `logging` | In-memory | Micrometer Observation API basics |
| Advanced | [`micrometer-tracing-coroutines`](observability/micrometer-tracing-coroutines/) | `micrometer`, `coroutines` | In-memory | Distributed tracing context through coroutine boundaries |
| Advanced | [`gatling-virtualthread-simulation`](gatling/virtualthread-simulation/) | `junit5` | App process | Gatling load simulation targeting virtual thread server |

```bash
./gradlew :observability-basic:test
./gradlew :gatling-virtualthread-simulation:gatlingRun
```

---

### 6. Architecture Extensions

> API Gateway, Spring Modulith, Security, Redis distributed patterns, AWS, Rate Limiting, Leader Election

| Level | Module | bluetape4k libs | Infra | Learning outcome |
|-------|--------|-----------------|-------|-----------------|
| Basic | [`spring-security-mvc`](spring-security/mvc/) | `logging`, `spring-boot4-core` | In-memory | Spring Security MVC: JWT, method security |
| Basic | [`spring-security-webflux`](spring-security/webflux/) | `coroutines`, `spring-boot4-core` | In-memory | Spring Security WebFlux: reactive filter chain |
| Advanced | [`spring-modulith-events-deep-dive`](spring-modulith/events-deep-dive/) | `coroutines`, `testcontainers` | PostgreSQL (TC) | Modulith application events with persistence |
| Advanced | [`spring-modulith-jpa-demo`](spring-modulith/jpa-demo/) | `logging`, `testcontainers` | PostgreSQL (TC) | Modulith module encapsulation with JPA |
| Advanced | [`gateway-api-gateway`](gateway/api-gateway/) | `logging` | Services running | Spring Cloud Gateway routing, filters, predicates |
| Basic | [`ratelimit-bucket4j-caffeine-web`](ratelimit/bucket4j-caffeine-web/) | `logging` | In-memory | Bucket4j rate limiting with Caffeine |
| Advanced | [`ratelimit-bucket4j-redis`](ratelimit/bucket4j-redis/) | `redis`, `testcontainers` | Redis (TC) | Distributed rate limiting via Bucket4j + Redis |
| Advanced | [`ratelimit-bucker4j-bluetape4k-webflux`](ratelimit/bucker4j-bluetape4k-webflux/) | `redis`, `coroutines`, `testcontainers` | Redis (TC) | bluetape4k WebFlux rate limit integration |
| Advanced | [`redis-distributed-lock`](redis/distributed-lock/) | `redis`, `redisson`, `coroutines` | Redis (TC) | Distributed lock with Redisson + coroutine suspend |
| Advanced | [`redis-cluster-demo`](redis/cluster-demo/) | `redis`, `redisson` | Redis Cluster (TC) | Redis Cluster topology and failover |
| Basic | [`redis-redisson-examples`](redis/redisson-examples/) | `redis`, `redisson` | Redis (TC) | Redisson data structures and pub/sub |
| Advanced | [`aws-s3-spring-cloud`](aws/s3-spring-cloud/) | `aws`, `testcontainers` | LocalStack (TC) | AWS S3 with Spring Cloud AWS + LocalStack |
| Advanced | [`leader-leader-election`](leader/) | `coroutines`, `redis`, `testcontainers` | Redis (TC) | Distributed leader election: blocking, coroutine, virtual thread |

```bash
./gradlew :spring-security-mvc:test
./gradlew :redis-distributed-lock:test
./gradlew :aws-s3-spring-cloud:test
```

---

## Tech Stack

| Item | Version |
|------|---------|
| Kotlin | 2.3.21 |
| JVM | 21 |
| Spring Boot | 4.0.6 |
| bluetape4k | 1.7.0 |
| Gradle | 8.x (Kotlin DSL, multi-module) |

---

## Project Structure

```
bluetape4k-workshop/
├── aws/                    # AWS SDK + Spring Cloud AWS
├── exposed/                # JetBrains Exposed ORM
├── gateway/                # API Gateway + microservices
├── gatling/                # Load/performance tests
├── io/                     # Okio I/O examples
├── json/                   # Jackson 3 serialization
├── kotlin/                 # Coroutines, design patterns
├── leader/                 # Distributed leader election
├── messaging/              # Kafka
├── observability/          # Micrometer Observation + Tracing
├── ratelimit/              # Bucket4j rate limiting
├── redis/                  # Redisson + Redis patterns
├── spring-boot/            # Spring Boot features
├── spring-data/            # JPA, R2DBC, MongoDB, Elasticsearch
├── spring-modulith/        # Spring Modulith events
├── spring-security/        # MVC/WebFlux security
├── vertx/                  # Vert.x + coroutines
├── virtualthreads/         # Virtual thread patterns
├── shared/                 # Shared test utilities
└── docs/
    ├── assets/             # Diagrams, images (see CONVENTIONS.md)
    ├── lessons/            # Engineering lessons learned
    └── governance/         # Project governance docs
```

---

## Contributing

See [AGENTS.md](AGENTS.md) for AI-agent guidance and the engineering workflow.
For human contributors, open an issue or draft PR — all feedback is welcome.

---

## License

[MIT](LICENSE) © bluetape4k contributors
