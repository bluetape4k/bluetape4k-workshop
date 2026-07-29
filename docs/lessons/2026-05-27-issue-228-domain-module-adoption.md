# Issue 228: domain-specific bluetape4k module adoption

Issue: #228
Date: 2026-05-27

## 배경

Issue #228은 workshop example 영역이 더 domain-specific한 `bluetape4k-*` module을
채택해야 하는지 감사했다. acceptance criteria는 각 candidate에 대해
adopt/defer/reject 결정을 요구했고, unused dependency를 명시적으로 금지했다.

## 결정 또는 발견 사항

이번 pass에서는 현재 dependency graph를 유지한다. 감사한 영역은 예제가 해당 domain을
다루는 곳에서 이미 대응되는 bluetape4k module을 사용하고 있으며, 남은 candidate는
unused가 되거나 behavior-changing rewrite를 요구한다.

pattern scan을 만족시키기 위해서만 bluetape4k module을 추가하지 않는다. example
source가 해당 module의 API를 import하거나, focused follow-up이 code를 그 module의
contract를 사용하도록 변경할 때 추가한다.

## Candidate Matrix

| Area | Decision | Source-backed rationale |
| --- | --- | --- |
| `json/*` | Reject standalone adoption | `bluetape4k-json` is an interface-level module that arrives transitively through concrete JSON integrations. Workshop JSON examples should continue to use concrete integrations such as `bluetape4k-jackson3` unless an interface-level JSON example is added. |
| `messaging/transactional-outbox` | Defer `bluetape4k-kafka4` | The module currently depends on Kafka clients and Spring Kafka directly in `messaging/transactional-outbox/build.gradle.kts`. `OutboxPublisher.publishEvent` intentionally blocks on `kafkaTemplate.send(...).get()` before marking a row published, preserving the transactional outbox success contract. `bluetape4k-kafka4` provides coroutine and Spring `suspendSend` helpers, but adopting those would require a suspend/reactive publisher design instead of a dependency-only edit. |
| `observability/micrometer-observation` | Adopted already | `observability/micrometer-observation/build.gradle.kts` already uses `libs.bluetape4k.micrometer`; the tests now use bluetape4k assertions from the root default test dependency. No additional `bluetape4k-coroutines` dependency is needed because this module does not model coroutine execution. |
| `redis/cluster-demo` | Adopted already | `redis/cluster-demo/build.gradle.kts` already uses `bluetape4k-lettuce`, `bluetape4k-testcontainers`, `bluetape4k-coroutines`, and `bluetape4k-idgenerators`. Redisson is commented out because the cluster demo uses Spring Data Redis with Lettuce; adding `bluetape4k-redisson` would be unused. |
| `vertx/*` | Adopted already | `vertx/coroutines`, `vertx/vertx-sqlclient`, and `vertx/vertx-webclient` already depend on `bluetape4k-vertx`; the coroutine examples also use `bluetape4k-coroutines` and concrete Vert.x dependencies. |
| `spring-data/r2dbc-*` | Adopted already | `spring-data/r2dbc-examples`, `spring-data/r2dbc-coroutines`, and `spring-data/r2dbc-webflux` already use `bluetape4k-r2dbc`; the Exposed WebFlux R2DBC module uses the Exposed R2DBC helper modules instead, matching its domain boundary. |
| `spring-data/jpa-querydsl` | Adopted already | `spring-data/jpa-querydsl/build.gradle.kts` already uses `bluetape4k-hibernate` because the example domain is JPA/Hibernate based. |
| `spring-data/mongodb-*` | Defer `bluetape4k-mongodb` | The MongoDB examples currently use Spring Data MongoDB, Kotlin MongoDB drivers, Testcontainers, and coroutine/reactor helpers directly. There is no existing import from `bluetape4k-mongodb` in the example source, and no local source evidence that a dependency-only addition would be used. A follow-up should first identify a concrete `bluetape4k-mongodb` API to demonstrate. |

## 결과

Gradle dependency는 추가하지 않았다. 이 이슈의 결과는 dependency churn을 막으면서도
Kafka와 MongoDB에 대한 명확한 follow-up gate를 남기는 source-backed adoption matrix다.

## 검증

- `messaging`, `observability`, `redis`, `vertx`, `spring-data`의 candidate build file과
  source import를 검사했다.
- local `bluetape4k-kafka4` source를 검사하고 사용 가능한 coroutine/Spring
  suspend-send helper를 확인했다.

## 향후 지침

향후 이슈에서 defer된 영역을 다시 다룰 때는 다음을 따른다.

1. version catalog가 아니라 source usage에서 시작한다.
2. source import가 동반되거나 behavior-preserving migration이 있을 때만 bluetape4k
   dependency를 추가한다.
3. root `bluetape4k-dependencies` BOM을 계속 사용하고, 개별 bluetape4k module version을
   pin하지 않는다.
4. `messaging/transactional-outbox`에서는 Kafka send success가 확인된 뒤에만 outbox row를
   `PUBLISHED`로 표시한다는 보장을 보존한다.
5. MongoDB는 Gradle dependency를 변경하기 전에 현재 `bluetape4k-mongodb` API를 먼저
   검증하고 focused example을 선택한다.
