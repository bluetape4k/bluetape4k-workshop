# Issue #892 JaVers history limit pushdown 설계

## 1. 목표

`exposed/javers-persistence-audit`와 `exposed/javers-approval-workflow`의 history 조회를
`bluetape4k-dependencies` 2.0.0이 제공하는 JaVers query limit 계약에 맞춘다. 호출자가
요청한 상한은 응답 후처리용 `.take()`가 아니라 `QueryBuilder.limit(...)`에 전달되어야 하며,
반환 목록은 JaVers의 newest-first 순서를 그대로 노출한다.

## 2. 근거

- GNO `bluetape4k-github`: workshop Issue #892, `bluetape4k-javers` Issue #309와 merged PR #320.
- upstream `AggregateRepository.loadHistory(id, limit)`는 기본 100, 양수 검증,
  `QueryBuilder.limit(...)`, newest-first 계약을 사용한다.
- 현재 workshop의 두 `getHistory`는 limit 없는 query를 실행한 뒤 전체 목록을
  `commitDate` 기준 oldest-first로 정렬한다.
- `bluetape4k-docs`와 `bluetape4k-wiki`에는 이 workshop 적용에 대한 별도 기록이 없다.

## 3. 공개 계약

두 서비스는 다음 형태를 제공한다.

```kotlin
@JvmOverloads
fun getHistory(id: IdType, limit: Int = DEFAULT_HISTORY_LIMIT): List<CdoSnapshot>
```

- `DEFAULT_HISTORY_LIMIT = 100`, `MAX_HISTORY_LIMIT = 100`이다.
- 허용 범위는 `1..100`이다. 0, 음수, 101 이상은 `IllegalArgumentException`으로 거부한다.
- 기존 Kotlin 한 인자 호출과 Java/JVM 한 인자 descriptor를 모두 유지한다.
- unknown id는 예외가 아니라 빈 목록을 반환한다.
- 반환 순서는 newest-first다. limit=1이면 가장 최근 snapshot만 반환한다.
- query 결과를 다시 정렬하거나 `.take()`로 줄이지 않는다.
- limit 실패 메시지는 식별자나 snapshot payload를 포함하지 않는다.

기존 예제의 oldest-first 표시는 2.0.0 소비 계약으로 변경된다. source/JVM 호출 호환성은
유지하지만 결과 ordering은 newest-first로 명시적으로 migration한다.

## 4. 구현 경계

각 서비스는 검증된 id와 limit으로 `JqlQuery`를 만들고 production `getHistory`가
`QueryBuilder.limit(...)`를 직접 호출한다. 테스트는 production helper로 기대값을 만들지 않고,
실제 서비스 결과와 Redis decode 횟수를 독립적으로 관찰한다.

```kotlin
val query = QueryBuilder.byInstanceId(orderId, Order::class.java)
    .limit(limit)
    .build()
```

### 4.1 Redis bounded read adapter

2.0.0이 선택하는 `javers-persistence-redis:1.0.0`의
`RedissonCdoSnapshotRepository.getStateHistory`는 `getAll()`로 모든 key와 snapshot을 읽은 뒤
`QueryParams.limit()`을 적용한다. 따라서 `QueryBuilder.limit`만 추가해서는 Redis read/decode
경계가 제한되지 않는다.

`exposed/javers-persistence-audit`에는 workshop 전용
`BoundedRedissonCdoSnapshotRepository` adapter를 둔다.

- 기존 `RedissonCdoSnapshotRepository`에 저장·head·일반 query를 위임한다.
- 다음 명시적 allowlist를 모두 만족하는 query만 fast path로 처리한다: 단일 exact `globalId`,
  `isAggregate == false`, `skip == 0`, `snapshotQueryLimit` 미설정, 그리고 `commitIds`,
  `toCommitId`, `version`/`fromVersion`/`toVersion`, `author`/`authorLikeIgnoreCase`,
  `from`/`fromInstant`/`to`/`toInstant`, `changedProperties`, `snapshotType`,
  `commitProperties`/`commitPropertiesLike`가 모두 비어 있다.
- 위 allowlist 밖의 query는 의미 보존을 위해 기존 repository로 fallback한다.
- fast path는 `RListMultimap.get(globalId).range(-limit, -1)`을 사용한다. Redis/Redisson의
  inclusive negative index에서 `-1`은 마지막 원소이고 `-limit`은 마지막 `limit`개 구간의
  시작이다. 이 단일 range는 빈 목록과 `size < limit`을 자연스럽게 처리하며 size 조회와
  concurrent append 사이의 TOCTOU를 만들지 않는다.
- 저장 순서는 oldest-first이므로 선택된 byte 구간만 역순으로 decode해 newest-first로 반환한다.
- adapter와 delegate는 동일 `JaversCodec`과 Redis key/Redisson codec을 사용한다.
- `setJsonConverter`를 delegate와 adapter 양쪽에 전달한다.
- adapter가 upstream과 동일한 Redis codec을 명시적으로 사용하도록 기존 versionless
  `libs.bluetape4k.redisson` alias를 direct dependency로 선언한다. 새 외부 dependency, schema,
  snapshot/commit policy 변경은 없다.

이 경계는 `limit=1/2`일 때 실제 decode 횟수가 각각 1/2임을 counting codec으로 검증한다.

## 5. 테스트 전략

### persistence-audit

- 3개 snapshot 뒤 `getHistory(id, 1)`이 최신 terminal snapshot 하나를 반환한다.
- `getHistory(id, 2)`가 최신 두 snapshot을 newest-first로 반환한다.
- Redis-backed production 호출의 codec decode 횟수가 요청 limit을 넘지 않는다.
- empty history와 `size < limit` history가 각각 빈 목록/전체 bounded 목록을 반환한다.
- aggregate, skip, author/date/version/commitIds/changedProperties/snapshotType/commitProperties
  query가 fast path를 사용하지 않고 delegate 결과를 보존하는지 table-driven regression으로 검증한다.
- 0, 음수, 101을 거부한다.
- 100과 기본 한 인자 호출이 성공하고 기본 호출이 최대 100개를 반환한다.
- unknown id는 빈 목록이다.
- reflection으로 `getHistory(String)`과 `getHistory(String, int)`를 실제 호출한다.
- terminal history와 unknown id를 구분하고 최신 revision/type을 함께 검증한다.

### approval-workflow

- initial/update history는 newest-first다.
- `limit=1`은 latest approved snapshot만 반환한다.
- rejected proposal은 bounded approved history에 snapshot을 추가하지 않는다.
- production 호출 결과가 requested limit을 따른다.
- 0, 음수, 101과 invalid policy id를 거부한다.
- 100과 기본 한 인자 호출이 성공한다.
- reflection으로 `getHistory(long)`과 `getHistory(long, int)`를 실제 호출한다.

## 6. 성능·안정성·보안

- Redis exact-instance fast path의 read/decode와 반환 materialization 크기는 요청 limit에 묶인다.
- filter가 추가된 일반 JaVers query는 의미 보존을 위해 기존 repository로 fallback하므로 이
  adapter의 bounded-read 보장을 받지 않는다.
- 100 hard cap은 예제 API가 무제한 caller input을 storage query에 전달하지 않게 한다.
- JaVers/Redis/Exposed storage schema와 commit policy는 바꾸지 않는다.
- 정렬용 추가 목록과 전체 history materialization을 제거한다.
- raw snapshot, proposal 내용, customer data를 validation/log message에 추가하지 않는다.
- history가 빈 목록이라는 사실만으로 entity 존재 여부를 판단하지 않는다. unknown id와 아직
  commit되지 않은 id는 모두 빈 목록이며, 존재 여부는 materialized store에서 별도 확인한다.
- 반환되는 `CdoSnapshot`은 원본 field를 포함하므로 HTTP/API 노출 시 호출자가 authorization과
  redaction을 적용해야 한다.

## 7. 의존성과 소비자 경계

- root `platform(libs.bluetape4k.dependencies)` 2.0.0만 version authority로 사용한다.
- `libs.bluetape4k.javers.*` alias는 versionless 상태를 유지한다.
- 개별 JaVers BOM, explicit module version, 2.1.0-SNAPSHOT을 추가하지 않는다.
- 기존 versionless `libs.bluetape4k.redisson` alias 외에 dependency를 새로 추가하지 않는다.

## 8. 문서·운영 가드

- 두 module `README.md`/`README.ko.md`에 bounded query, 1..100, newest-first,
  ordering migration과 unknown-id semantics를 동등하게 기록한다.
- root README pair와 coverage matrix의 module 설명을 갱신한다.
- lesson과 lessons index를 추가한다.
- ecosystem reuse manifest에 Issue #892 scope를 등록한다.
- `Examples.yml`의 기존 smoke/full module registration은 유지하고 stale-check에
  Issue #892 query limit 계약을 추가한다.

## 9. 제외 범위

- cursor/page token 및 암호화
- snapshot schema와 commit metadata 변경
- upstream JaVers repository와 Redis schema 변경
- web controller 또는 새 HTTP endpoint
- Kafka projection과 cross-store transaction 설계

## 10. 완료 조건

- 설계·계획 리뷰 P0/P1 0건
- failing regression 후 구현 통과
- 두 module clean tests, root detekt 통과
- README language/parity, stale-check, ecosystem checker, actionlint 통과
- dependency insight에서 JaVers artifact가 dependencies 2.0.0 constraint로 resolve
- PR exact-head hosted CI 전체 통과, CLEAN/MERGEABLE, milestone 2.0.0, assignee debop
