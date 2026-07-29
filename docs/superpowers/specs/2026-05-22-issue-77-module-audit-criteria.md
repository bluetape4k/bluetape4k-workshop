# Issue #77 — 모듈 감사 및 Basic/Advanced 분류 기준

**날짜**: 2026-05-22
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/77
**부모 에픽**: #76
**상태**: 초안

---

## 1. 감사방법론

### 점수 측정기준

각 모듈은 다음 네 가지 차원에 걸쳐 점수가 매겨집니다.

| 차원 | 신호 | 무게 |
|-----------|--------|--------|
| **BT-참조 횟수** | `build.gradle.kts`의 `bluetape4k` 참조 | 기본 |
| **특정 BT 모듈** | 높은 가치의 라이브러리: `bucket`, `cache`, `redis`, `virtualthread`, `exposed`, `kafka`, `micrometer`, `resilience`, `hibernate`, `r2dbc` | 보조 |
| **생산원** | `src/main` Kotlin 파일 수 | 보조 |
| **테스트 범위** | `src/test` Kotlin 파일 수 | 3차 |

### BT 가치 점수

| 점수 | 기준 |
|-------|----------|
| **HIGH** | bt-ref ≥ 6 **또는** (bt-ref ≥ 4 및 ≥ 3 고가치 BT 라이브러리) |
| **MEDIUM** | bt-ref 3–5 및 최소 1개의 고가치 BT lib |
| **LOW** | bt-ref ≤ 2 **또는** `core`/`coroutines`/`junit`/`io`만(인프라 전용, 도메인 BT 값 없음) |

### 평결 카테고리

| 평결 | 의미 |
|---------|---------|
| **KEEP** | 있는 그대로 유지합니다. 이미 BT 값을 보여줍니다 |
| **CONVERT** | 유지하되 늘려야 함 BT - 다음 서사시 작업에서 첫 번째 사용 |
| **ARCHIVE** | `_archive/`으로 이동 또는 `settings.gradle.kts`에서 제거 |
| **REWRITE** | 전체 교체 예정(별도의 문제로 추적) |

---

## 2. 기본/고급 분류 기준

### 기본 수준

**기본** 예는 다음 ALL을 충족합니다.
- 하나의 기본 `bluetape4k-*` 라이브러리 또는 바로가기를 보여줍니다.
- 단일 `./gradlew :{module}:test` 또는 `bootRun` 명령으로 실행 가능
- BT이 제거하는 상용구(before/after 또는 인라인 주석)를 표시합니다.
- Docker/Testcontainers 이외의 외부 전제조건은 필요하지 않습니다.
- README는 BT의 이점을 5문장 이하로 설명합니다.

### 고급 레벨

**고급** 예시는 다음 ALL을 충족합니다.
- 조정된 시나리오에서 두 개 이상의 BT 라이브러리를 보여줍니다.
- 트랜잭션, 동시성, 관찰 가능성, 오류 처리, 성능, 분산 인프라 또는 모듈 간 구성 등 하나 이상의 생산 문제를 다룹니다.
- 실행 가능한 Spring 진입점, API 엔드포인트 및 통합 테스트가 있습니다.
- README에는 아키텍처 다이어그램(ERD 또는 시퀀스)과 `Used Bluetape4k features` 테이블이 포함됩니다.

### 단일 수준 예외

다음과 같은 경우 모듈은 단일 수준으로 유지될 수 있습니다.
- 도메인에는 자연스러운 "복잡성 추가" 차원이 없습니다(예: `io/okio-examples` — Okio가 전체 내용입니다).
- 감사 테이블에 이유를 명시적으로 문서화하세요.

---

## 3. 모듈 감사 테이블(61개 활성 모듈)

> **활성**: 2026년 5월 22일 현재 `settings.gradle.kts`에 등록되어 있습니다.
> `bt-ref` = `build.gradle.kts`의 bluetape4k 참조입니다.
> `src/test` = Kotlin 파일은 `src/main` / `src/test` 아래에 포함됩니다.

### 도메인: 데이터 액세스

| 모듈 | bt-ref | src/test | BT 점수 | 평결 | 레벨 | 메모 |
|--------|-------:|----------|----------|---------|-------|-------|
| `exposed/domain` | 7 | 2/163 | HIGH | **REWRITE** | — | 테스트 전용 API 덤프; 앱 구조가 없습니다. → #97 |
| `exposed/dao-web-transaction` | 5 | 12/3 | MEDIUM | **REWRITE** | — | 부분적인 앱 모양. → #97 (MVC+JDBC 스타일) |
| `exposed/spring-transaction` | 3 | 0/23 | MEDIUM | **REWRITE** | — | `src/main`이 아닙니다. 테스트 전용입니다. → #97 |
| `exposed/sql-web-virtualthread` | 7 | 22/8 | HIGH | **REWRITE** | — | 좋은 VT+JDBC 패턴입니다. → #97 (MVC+VT 스타일) |
| `exposed/sql-webflux-coroutines` | 5 | 21/8 | MEDIUM | **REWRITE** | — | WebFlux+R2DBC 모양이 좋습니다. → #97 (WebFlux+코루틴 스타일) |
| `spring-data/r2dbc-examples` | 6 | 8/6 | HIGH | KEEP | 기본 | R2DBC entity/repo 기본; BT R2DBC + Spring Boot |
| `spring-data/r2dbc-coroutines` | 8 | 11/5 | HIGH | KEEP | 고급 | BT R2DBC 코루틴 패턴 + Testcontainers |
| `spring-data/r2dbc-webflux` | 5 | 10/7 | MEDIUM | CONVERT | 고급 | 테스트가 비활성화되었습니다(#120). 스키마 초기화 수정이 필요합니다 |
| `spring-data/r2dbc-webflux-exposed` | 3 | 11/7 | LOW | CONVERT | 고급 | 낮은 BT 사용량; BT Exposed R2DBC 도우미 추가 |
| `spring-data/jpa-querydsl` | 4 | 12/7 | MEDIUM | KEEP | 기본 | BT 최대 절전 모드 + QueryDSL; 클래식 JPA 패턴 |
| `spring-data/mongodb-coroutines` | 5 | 9/10 | MEDIUM | KEEP | 고급 | BT 코루틴 + MongoDB 비동기 |
| `spring-data/mongodb-transactions` | 4 | 9/4 | MEDIUM | KEEP | 기본 | MongoDB 다중 문서 트랜잭션 |
| `spring-data/elasticsearch` | 6 | 6/5 | HIGH | KEEP | 기본 | BT 잭슨 + Spring Boot ES |
| `spring-data/elasticsearch-webflux` | 5 | 18/8 | MEDIUM | KEEP | 고급 | ES WebFlux 반응 경로 |
| `spring-data/redis-examples` | 9 | 15/16 | HIGH | KEEP | 고급 | 높음 BT: redis, idgenerators, spring.boot, testcontainers |
| `vertx/vertx-sqlclient` | 7 | 0/7 | HIGH | KEEP | 고급 | BT Vert.x SQL 클라이언트; 테스트 전용이지만 높음 BT |

### 도메인: Spring Boot 작업

| 모듈 | bt-ref | src/test | BT 점수 | 평결 | 레벨 | 메모 |
|--------|-------:|----------|----------|---------|-------|-------|
| `spring-boot/cache-caffeine` | 5 | 6/3 | MEDIUM | KEEP | 기본 | BT 캐시.코어 + 카페인 |
| `spring-boot/cache-redis` | 8 | 6/3 | HIGH | KEEP | 고급 | BT 양상추 + Redis 캐시; spring.boot 스타터 |
| `spring-boot/resilience4j-coroutines` | 4 | 20/12 | MEDIUM | KEEP | 고급 | BT 탄력성 + 코루틴; 좋은 생산 패턴 |
| `spring-boot/problem` | 4 | 11/3 | MEDIUM | KEEP | 기본 | BT 탄력성 이슈 세부사항 |
| `spring-boot/webflux-coroutines` | 5 | 8/5 | MEDIUM | KEEP | 기본 | BT 코루틴 WebFlux; 보급형 |
| `spring-boot/webflux-websocket` | 6 | 7/1 | HIGH | KEEP | 고급 | BT ID 생성기 + WebSocket 반응성 |
| `spring-boot/chaos-monkey` | 4 | 5/2 | MEDIUM | KEEP | 고급 | 카오스 + BT 코루틴; 생산 탄력성 |
| `spring-boot/application-event-demo` | 3 | 14/2 | MEDIUM | KEEP | 기본 | BT 코루틴을 사용한 스프링 이벤트 |
| `spring-boot/stomp-websocket` | 4 | 6/2 | MEDIUM | KEEP | 기본 | STOMP/WebSocket 패턴 |
| `spring-boot/async-logging` | 2 | 3/2 | LOW | **ARCHIVE** | — | 아래의 BT 코루틴만; 도메인 BT 값 없음 |
| `spring-boot/cbor-mvc` | 3 | 5/2 | LOW | CONVERT | 기본 | CBOR 직렬화 틈새; BT Jackson3 경로가 필요합니다 |
| `spring-boot/protobuf-mvc` | 3 | 5/3 | LOW | CONVERT | 기본 | Protobuf/gRPC; BT grpc 도우미 추가 |
| `gateway/api-gateway` | 9 | 5/2 | HIGH | KEEP | 고급 | 높음 BT: 버킷, 캐시, 탄력성, netty |
| `gateway/customers` | 5 | 7/0 | MEDIUM | KEEP | 고급 | 마이크로서비스 형태 BT 코루틴 + 네티 |
| `gateway/orders` | 5 | 9/1 | MEDIUM | KEEP | 고급 | 마이크로서비스 형태 BT ID 생성기 |
| `spring-modulith/events-deep-dive` | 3 | 28/8 | MEDIUM | KEEP | 고급 | 스프링 계수 + BT 최대 절전 모드 + ID 생성기 |
| `spring-modulith/jpa-demo` | 4 | 24/4 | MEDIUM | KEEP | 기본 | 모듈리스 기본 + BT 최대 절전 모드 |

### 도메인: 직렬화 및 메시징

| 모듈 | bt-ref | src/test | BT 점수 | 평결 | 레벨 | 메모 |
|--------|-------:|----------|----------|---------|-------|-------|
| `json/jackson-examples` | 5 | 1/14 | MEDIUM | KEEP | 기본 | BT Jackson3 기능; 테스트가 풍부한 |
| `json/jsonview-examples` | 6 | 5/2 | HIGH | KEEP | 고급 | BT 잭슨3 JsonView; 더 많은 테스트가 필요합니다 |
| `io/okio-examples` | 5 | 42/39 | MEDIUM | KEEP | 기본 | BT 오키오; 단일 레벨 OK (Okio가 이야기입니다) |
| `messaging/kafka` | 7 | 12/3 | HIGH | KEEP | 기본 | BT Kafka + 코루틴; 스프링 Kafka 기본 |
| `messaging/kafka-reply` | 6 | 5/1 | HIGH | KEEP | 고급 | BT Kafka 응답 패턴; 요청-응답 흐름 |

### 도메인: 비동기 및 반응성

| 모듈 | bt-ref | src/test | BT 점수 | 평결 | 레벨 | 메모 |
|--------|-------:|----------|----------|---------|-------|-------|
| `kotlin/coroutines` | 4 | 0/34 | MEDIUM | KEEP | 기본 | BT 코루틴 패턴; 테스트 전용 OK 학습용 |
| `kotlin/design-patterns` | 3 | 29/9 | LOW | KEEP | 고급 | Kotlin의 디자인 패턴; BT 코루틴 + IO |
| `kotlin/workshop` | 3 | 0/4 | LOW | **ARCHIVE** | — | 4개의 테스트 파일만; 식별 가능한 BT 값 없음 |
| `reactive/mutiny` | 2 | 0/12 | LOW | **ARCHIVE** | — | Quarkus 인접; quarkus/도메인이 이미 비활성화되었습니다 |
| `vertx/coroutines` | 5 | 2/1 | MEDIUM | KEEP | 기본 | BT Vert.x 코루틴; 최소한이지만 집중된 |
| `vertx/vertx-webclient` | 5 | 0/4 | MEDIUM | KEEP | 고급 | BT Vert.x WebClient; 고차 Vert.x |

### 도메인: 관찰 가능성 및 성능

| 모듈 | bt-ref | src/test | BT 점수 | 평결 | 레벨 | 메모 |
|--------|-------:|----------|----------|---------|-------|-------|
| `observability/micrometer-observation` | 4 | 8/3 | MEDIUM | KEEP | 기본 | BT Micrometer 관찰 API |
| `observability/micrometer-tracing-coroutines` | 6 | 11/6 | HIGH | KEEP | 고급 | BT Micrometer 추적 + 코루틴 + Testcontainers |
| `gatling/gradle-plugin-demo` | 0 | 0/0 | LOW | **ARCHIVE** | — | BT 참조 0개, 소스 0개; Gradle 플러그인 구성만 |
| `gatling/virtualthread-simulation` | 6 | 7/3 | HIGH | KEEP | 고급 | BT 개틀링 + 가상 스레드; 부하 테스트 패턴 |
| `virtualthreads/rules` | 4 | 0/13 | MEDIUM | KEEP | 기본 | BT VirtualThread API 규칙; 시험전용 학습 |
| `virtualthreads/spring-mvc-tomcat` | 9 | 18/8 | HIGH | KEEP | 고급 | 높음 BT: 캐시, 코어, virtualthread.api/jdk, 최대 절전 모드 |
| `virtualthreads/spring-webflux` | 9 | 12/7 | HIGH | KEEP | 고급 | 높음 BT: 코어, virtualthread.api/jdk; MVC과 비교 |

### 도메인: 아키텍처 확장

| 모듈 | bt-ref | src/test | BT 점수 | 평결 | 레벨 | 메모 |
|--------|-------:|----------|----------|---------|-------|-------|
| `aws/s3-spring-cloud` | 4 | 1/2 | MEDIUM | KEEP | 기본 | BT AWS + 잭슨;  #107에 대한 확장이 필요함 |
| `redis/cluster-demo` | 7 | 2/3 | HIGH | KEEP | 고급 | BT Redis 클러스터 + ID 생성기; Redis 토폴로지 |
| `redis/redisson-examples` | 8 | 0/40 | HIGH | KEEP | 고급 | 높음 BT: 캐시, redis, grpc, idgenerators; 풍부한 테스트 스위트 |
| `ratelimit/bucker4j-bluetape4k-webflux` | 7 | 12/3 | HIGH | KEEP | 고급 | BT 버킷 + Redis + WebFlux; 생산율 제한 |
| `ratelimit/bucket4j-caffeine-web` | 3 | 2/1 | LOW | CONVERT | 기본 | 낮음 BT; BT 버킷 도우미 경로가 필요합니다 |
| `ratelimit/bucket4j-redis` | 6 | 5/3 | HIGH | KEEP | 고급 | BT Redis + 버킷; 분산 속도 제한 |
| `spring-security/mvc/hello` | 3 | 3/2 | LOW | CONVERT | 기본 | 낮음 BT; BT jwt/security 도우미 추가 |
| `spring-security/webflux/hello-security` | 4 | 3/2 | MEDIUM | KEEP | 기본 | BT 코루틴 + 보안 |
| `spring-security/webflux/jwt` | 4 | 4/2 | MEDIUM | KEEP | 고급 | BT 코루틴 + JWT; 대응적 보안 |
| `mapping/mapstruct` | 1 | 1/3 | LOW | **ARCHIVE** | — | BT io 인프라만; MapStruct는 BT 기능이 아닙니다 |

---

## 4. 요약통계

| 평결 | 카운트 |
|---------|------:|
| KEEP | 40 |
| CONVERT | 6 |
| ARCHIVE | 6 |
| REWRITE (→ #97) | 5 |
| **합계** | **57** |

### 아카이브 후보(#78 범위)

| 모듈 | 이유 |
|--------|--------|
| `spring-boot/async-logging` | bt-ref=2; 로깅 인프라만 있고 도메인은 없음 BT |
| `kotlin/workshop` | bt-ref=3; 4개의 테스트 파일; 학습 성과가 눈에 띄지 않음 |
| `reactive/mutiny` | bt-ref=2; Quarkus 인접; `quarkus/` 도메인이 이미 비활성화되었습니다 |
| `gatling/gradle-plugin-demo` | bt-ref=0; 제로 소스; Gradle 구성 데모만 |
| `mapping/mapstruct` | bt-ref=1; MapStruct은(는) Bluetape4k 기능이 아닙니다 |

> 참고: `quarkus/hibernate-reactive-panache` 및 `quarkus/rest-coroutine`은 `settings.gradle.kts`에서 이미 주석 처리되었습니다.

### 후보자 변환(BT 가치 개선 필요)

| 모듈 | 필수 조치 |
|--------|----------------|
| `spring-data/r2dbc-webflux` | 비활성화된 테스트(#120)를 먼저 수정한 다음 승격 |
| `spring-data/r2dbc-webflux-exposed` | BT Exposed R2DBC 저장소 도우미 추가 |
| `spring-boot/cbor-mvc` | BT Jackson3 CBOR 코덱 경로 추가 |
| `spring-boot/protobuf-mvc` | BT gRPC 도우미 사용법 추가 |
| `ratelimit/bucket4j-caffeine-web` | 원시가 아닌 BT 버킷 추상화 표시 Bucket4j |
| `spring-security/mvc/hello` | BT security/jwt 도우미 추가 |

### 후보 재작성(→ Issue #97)

5개의 `exposed/` 모듈은 모두 3개의 프로덕션 형태 앱으로 대체됩니다.
1. `exposed/mvc-jdbc` (MVC + JDBC + Exposed JDBC)
2. `exposed/mvc-virtualthread` (MVC + 가상 스레드 + Exposed JDBC)
3. `exposed/webflux-r2dbc` (WebFlux + 코루틴 + Exposed R2DBC)

---

## 5. 새로운 예시 백로그 격차(→ Issue #92)

**제로 또는 씬** 워크숍 적용 범위를 갖춘 Bluetape4k 라이브러리:

| BT 도서관 | 현재 적용 범위 | 우선순위 |
|------------|-----------------|----------|
| `bluetape4k-leader` | 없음 | HIGH (#106) |
| `bluetape4k-javers` | 없음 | HIGH (#100) |
| `bluetape4k-image` | 없음 | MEDIUM (#93, #94) |
| `bluetape4k-text` | 없음 | MEDIUM (#105) |
| `bluetape4k-idgenerators` | Thin(redis/exposed 모듈에 내장됨) | MEDIUM (#62 부분) |
| 멱등성 패턴 | 없음 | HIGH (#98) |
| 거래 발신함 | 없음 | HIGH (#99) |
| 다중 테넌트 격리 | 없음 | HIGH (#104) |

---

## 6. 승인 기준( #77에 대한 DoD)

- [x] 57개 이상의 모든 활성 모듈이 점수를 매겼습니다(BT 값 + 판정 + 수준).
- [x] Basic/Advanced 예시로 정의된 분류 기준
- [x]  #78에 대해 생성된 아카이브 후보 목록
- [x] 도메인 에픽(#79–#88)에 대해 생성된 후보 목록 변환
- [x]  #97에 대한 재작성 범위가 확인되었습니다.
- [x] BT #92에 대해 생성된 보장 공백 목록
