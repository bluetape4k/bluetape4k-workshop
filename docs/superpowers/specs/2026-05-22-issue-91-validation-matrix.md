# Issue #91 — 워크숍 검증 매트릭스

**날짜**: 2026-05-22
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/91
**부모 에픽**: #76
**상태**: 활성

> 현재 그래프 수정 사항(2026-08-27, #558): 이 체크아웃은 추가 후 Gradle에 129개의 프로젝트를 등록합니다.
> 5개의 `:commerce-usage-billing-*-service` 모듈,
> `:commerce-usage-billing-microservices-composition-tests` 그리고
> `:commerce-event-sourced-promotion-voucher-campaign`. 아래의 23/35 분류는 현재 등록 기준입니다.
> #91 기계적으로 증가하는 재고가 아닌 기준선입니다. 현재 실행 가능한 레인은 다음과 같습니다.
> `scripts/smoke-validate.sh` 및 `.github/workflows/Examples.yml`에 유지됩니다.

---

## 1. 검증 계층

| 계층 | 트리거 | 범위 | 도커 필요 |
|------|---------|-------|-----------------|
| **T1 컴파일** | 모든 푸시 / PR | `./gradlew build -x test` 현재 129개 프로젝트 그래프 전체 | 아니요 |
| **T2 연기** | 평일(야간) | 인메모리 모듈만(Testcontainers 없음) | 아니요 |
| **T3 전체** | 컨테이너 레인 및 전체 로컬 그룹의 예 | Commerce | 예 |
| **T4 로컬 그룹** | 개발자 편의성 | 도메인별 `scripts/smoke-validate.sh <group>` | 의존한다 |

---

## 2. 원본 #91 모듈 분류

### T2 연기 — 없음 Testcontainers (23개 모듈)

| 모듈(Gradle 프로젝트) | 도메인 그룹 |
|-------------------------|-------------|
| `:jackson-examples` | 직렬화 |
| `:jsonview-examples` | 직렬화 |
| `:okio-examples` | 직렬화 |
| `:kotlin-design-patterns` | Async/Reactive |
| `:spring-boot-application-event-demo` | Spring Boot 운영 |
| `:spring-boot-cache-caffeine` | Spring Boot 운영 |
| `:spring-boot-cbor-mvc` | Spring Boot 운영 |
| `:spring-boot-chaos-monkey` | Spring Boot 운영 |
| `:spring-boot-problem` | Spring Boot 운영 |
| `:spring-boot-protobuf-mvc` | Spring Boot 운영 |
| `:spring-boot-resilience4j-coroutines` | Spring Boot 운영 |
| `:spring-boot-stomp-websocket` | Spring Boot 운영 |
| `:spring-boot-webflux-coroutines` | Spring Boot 운영 |
| `:spring-boot-webflux-websocket` | Spring Boot 운영 |
| `:spring-data-r2dbc-examples` | 데이터 액세스 |
| `:spring-data-r2dbc-webflux-exposed` | 데이터 액세스 |
| `:spring-modulith-events-deep-dive` | Spring Boot 운영 |
| `:spring-security-mvc-hello` | Spring Boot 운영 |
| `:spring-security-webflux-hello-security` | Spring Boot 운영 |
| `:spring-security-webflux-jwt` | Spring Boot 운영 |
| `:vertx-coroutines` | Async/Reactive |
| `:virtualthreads-rules` | Observability/Performance |
| `:micrometer-observation` | Observability/Performance |

> **알려진 건너뛰기**: `:spring-data-r2dbc-webflux` —  #120 스키마 수정이 보류 중인 테스트가 비활성화되었습니다.

### T3 전체 — Testcontainers (35개 모듈)

| 모듈 | 필요한 인프라 |
|--------|-----------------------|
| `:exposed-domain` | PostgreSQL |
| `:exposed-dao-web-transaction` | PostgreSQL |
| `:exposed-spring-transaction` | PostgreSQL |
| `:exposed-sql-web-virtualthread` | PostgreSQL |
| `:exposed-sql-webflux-coroutines` | PostgreSQL |
| `:spring-data-r2dbc-coroutines` | PostgreSQL |
| `:spring-data-r2dbc-webflux` | PostgreSQL (#120 비활성화됨) |
| `:spring-data-jpa-querydsl` | PostgreSQL |
| `:spring-data-mongodb-coroutines` | MongoDB |
| `:spring-data-mongodb-transactions` | MongoDB |
| `:spring-data-elasticsearch` | 엘라스틱서치 |
| `:spring-data-elasticsearch-webflux` | 엘라스틱서치 |
| `:spring-data-redis-examples` | Redis |
| `:spring-boot-cache-redis` | Redis |
| `:spring-modulith-jpa-demo` | PostgreSQL |
| `:messaging-kafka` | Kafka |
| `:messaging-kafka-reply` | Kafka |
| `:messaging-kafka-multi-broker-failover` | Kafka 3-broker KRaft (Colima/Docker) |
| `:redis-cluster-demo` | Redis 클러스터 |
| `:redis-redisson-examples` | Redis |
| `:bucker4j-bluetape4k-webflux` | Redis |
| `:bucket4j-redis` | Redis |
| `:micrometer-tracing-coroutines` | 집킨 / OTLP |
| `:gatling-virtualthread-simulation` | HTTP 대상 |
| `:kotlin-coroutines` | (기타 Testcontainers) |
| `:virtualthreads-spring-mvc-tomcat` | PostgreSQL |
| `:virtualthreads-spring-webflux` | PostgreSQL |
| `:vertx-vertx-sqlclient` | PostgreSQL |
| `:vertx-vertx-webclient` | HTTP 대상 |
| `:aws-s3-spring-cloud` | LocalStack S3 |
| `:api-gateway` | Redis + 다운스트림 |
| `:customers` | (현재 없음) |
| `:orders` | (현재 없음) |
| `:spring-data-r2dbc-webflux-exposed` | (H2 인메모리 — 연기 OK) |
| `:optimization-warehouse-allocation` | PostgreSQL |

---

## 3. 도메인별 연기 명령

`scripts/smoke-validate.sh <group>`을 통해 또는 직접 실행:

### 데이터 액세스(인메모리 하위 집합)
```bash
./gradlew :spring-data-r2dbc-examples:test :spring-data-r2dbc-webflux-exposed:test --continue
```

### 데이터 액세스(Testcontainers)
```bash
./gradlew :exposed-dao-web-transaction:test :exposed-spring-transaction:test \
  :exposed-sql-web-virtualthread:test :exposed-sql-webflux-coroutines:test \
  :spring-data-r2dbc-coroutines:test :spring-data-jpa-querydsl:test \
  :spring-data-mongodb-coroutines:test :spring-data-mongodb-transactions:test \
  :spring-data-elasticsearch:test :spring-data-elasticsearch-webflux:test \
  :spring-data-redis-examples:test --continue --max-workers=1
```

### Spring Boot 작업(연기)
```bash
./gradlew :spring-boot-application-event-demo:test :spring-boot-cache-caffeine:test \
  :spring-boot-problem:test :spring-boot-resilience4j-coroutines:test \
  :spring-boot-webflux-coroutines:test :spring-boot-webflux-websocket:test \
  :spring-boot-chaos-monkey:test :spring-boot-stomp-websocket:test \
  :spring-modulith-events-deep-dive:test \
  :spring-security-mvc-hello:test :spring-security-webflux-hello-security:test \
  :spring-security-webflux-jwt:test --continue
```

### 직렬화 및 메시징(연기)
```bash
./gradlew :jackson-examples:test :jsonview-examples:test :okio-examples:test --continue
```

### 직렬화 및 메시징(Testcontainers)
```bash
./gradlew :messaging-kafka:test :messaging-kafka-reply:test --continue --max-workers=1
./gradlew :messaging-kafka-multi-broker-failover:test --continue --max-workers=1
```

### 비동기식 및 반응성
```bash
./gradlew :kotlin-coroutines:test :kotlin-design-patterns:test \
  :vertx-coroutines:test :vertx-vertx-sqlclient:test :vertx-vertx-webclient:test --continue
```

### 관찰 가능성 및 성능
```bash
./gradlew :micrometer-observation:test :micrometer-tracing-coroutines:test \
  :virtualthreads-rules:test :virtualthreads-spring-mvc-tomcat:test \
  :virtualthreads-spring-webflux:test --continue --max-workers=1
```

### Redis / 아키텍처 확장
```bash
./gradlew :redis-cluster-demo:test :redis-redisson-examples:test \
  :bucker4j-bluetape4k-webflux:test :bucket4j-redis:test --continue --max-workers=1
```

### 상업(Testcontainers)

```bash
./gradlew :commerce-order-lifecycle-fulfillment:test \
  :commerce-reservation-control-plane:test \
  :commerce-promotion-voucher-campaign:test \
  :commerce-concert-ticket-flash-sale:test \
  :commerce-usage-billing-microservices-composition-tests:integrationTest \
  --continue --max-workers=1
```

### Optimization (Testcontainers)

```bash
./gradlew :optimization-planning-contracts:test \
  :optimization-field-service-dispatch:test \
  :optimization-warehouse-allocation:test \
  --continue --max-workers=1
```

---

## 4. 오래된 포함 가드

```bash
# Verify the current Gradle project graph and README references
EXPECTED_GRADLE_PROJECTS=129 ./scripts/smoke-validate.sh stale-check
# Expected: 129 for the current Gradle project graph (including #558)

# Verify removed modules are not referenced in any README
for m in async-logging kotlin/workshop reactive/mutiny gatling/gradle-plugin-demo mapping/mapstruct; do
  rg "$m" --include="*.md" . && echo "STALE: $m" || true
done
```

---

## 5. README 링크 확인

```bash
# Check all relative image links in READMEs resolve to existing files
fd README.md . --exclude .worktrees | xargs -I{} bash -c '
  dir=$(dirname {})
  rg "!\[.*\]\(([^)]+)\)" {} -o -r '"'"'$1'"'"' | while read link; do
    [[ "$link" =~ ^http ]] && continue
    [[ -f "$dir/$link" ]] || echo "BROKEN: {} → $link"
  done
'
```

---

## 6. 합격기준 현황

- [x] 정의된 검증 매트릭스(T1/T2/T3/T4 계층)
- [x] 모듈 분류: 23 연기 안전, 35 Testcontainers
- [x] 도메인별 연기 명령이 문서화되었습니다.
- [x] `scripts/smoke-validate.sh` 추가됨
- [x] 매일 T2 연기 테스트 작업으로 야간 CI 업데이트됨
- [x] 오래된 포함 확인: 현재 129개 프로젝트 그래프, 오래된 참조 없음
- [x] README 링크 확인 명령이 문서화됨
- [ ] #120(`r2dbc-webflux` 비활성화된 테스트)는 별도로 추적됨
