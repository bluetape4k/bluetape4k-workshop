# Lessons — exposed-mvc-jdbc BT Repository 강화

**Date**: 2026-05-24  
**Issue**: #79 (Data Access Basic)  
**Type**: Type-B Fast Track  
**Branch**: `feat/issue-79-data-access-basic`

---

## 근본 원인 / 동기

`exposed-mvc-jdbc`는 classpath에 `bluetape4k-exposed-core`와
`bluetape4k-exposed-jdbc`를 가지고 있었지만 API를 전혀 사용하지 않았다. table은
plain `Table`을 확장했고, repository는 class마다 약 35줄의 수동 CRUD를 갖고
있었으며 pagination, batchInsert, audit 지원이 없었다.

---

## 결정

### AuditableLongIdTable for AuthorTable

plain `LongIdTable`이 아니라 `AuditableLongIdTable`을 선택했다. 이유는 다음과 같다.

- Author는 user-managed resource이므로 누가 생성/수정했는지 남기는 audit trail에
  실제 가치가 있다.
- 4개 audit column은 `clientDefault`와 `defaultExpression`으로 자동 연결되므로
  insert 시점에 application code가 필요 없다.
- 명시적 context가 없으면 `UserContext`가 `"system"`을 기본값으로 사용하므로
  기존 seed code 변경이 필요 없다.

### LongIdTable for BookTable (no audit)

대비를 보여주고 Basic 예제의 secondary entity에 audit column을 추가하지 않기 위해
BookTable은 audit 없이 유지했다.

### LongAuditableJdbcRepository / LongJdbcRepository

이 interface들은 전체 CRUD surface를 상속한다.

- `findAll()`, `findById()`, `findByIdOrNull()`, `count()`, `existsById()`
- `deleteById()`, `deleteAll()`, `deleteAllByIds()`
- `findPage()` with `ExposedPage` (offset pagination)
- `batchInsert()`, `batchUpsert()`
- `auditedUpdateById()` (AuditableJdbcRepository only) — auto-sets `updatedAt`/`updatedBy`

repository 구현은 KDoc 포함 약 35줄에서 약 20줄로 줄었다.

### `exposed-java-time` 누락 dependency(구현 중 발견한 버그)

`AuditableIdTable`은 `timestamp()` column에 `org.jetbrains.exposed.v1.javatime.*`를
사용한다. `bluetape4k-exposed-core` POM은 `exposed-java-time`과
`exposed-kotlin-datetime` 중 하나를 강제하지 않기 위해 이를 `compileOnly`
dependency로 선언한다. downstream module은 `jetbrains.exposed.java.time`을
명시적으로 추가해야 한다.

**Pattern**: BT module의 table base class가 date/time column을 사용하면 항상
module의 build.gradle.kts에 `jetbrains.exposed.java.time` 또는
`exposed-kotlin-datetime`을 추가한다.

---

## 검증

```
Tests: 11 passing (AuthorControllerTest, BookController, ProductControllerTest,
                   OrderControllerTest, PlaceOrderRollbackTest, ConcurrentPlaceOrderTest)
./gradlew :exposed-mvc-jdbc:test — BUILD SUCCESSFUL
```

---

## 향후 지침

1. **`exposed-java-time`은 명시적이어야 한다**: javatime을 transitive classpath에
   의존하지 않는다. BT auditable table을 사용하면 추가한다.

2. **JdbcRepository의 `findById()`는 non-null이다**: 값이 없으면
   `NoSuchElementException`을 던진다. null이 유효한 결과라면 Elvis `?: throw`
   pattern이 아니라 `findByIdOrNull()`을 사용한다.

3. **vararg predicate syntax**: `findBy({ predicate })`를 사용한다. signature에서
   vararg 뒤에 다른 named parameter가 오면 lambda 주변에 명시적 괄호가 필요하다.

4. **EntityID<Long> propagation**: `AuthorTable`이
   `LongIdTable`/`AuditableLongIdTable`이 되면 `insertAndGetId`는
   `EntityID<Long>`을 반환한다. child table의 FK column은 `Column<EntityID<Long>>`이므로
   insert statement는 `EntityID<Long>`을 직접 받을 수 있다. `insertAndGetId` 반환값에는
   `EntityID(raw, table)` wrapper가 필요 없지만, plain Long에서 insert할 때는
   `it[authorId] = EntityID(req.authorId, AuthorTable)`처럼 Long → EntityID wrapping이
   필요하다.
