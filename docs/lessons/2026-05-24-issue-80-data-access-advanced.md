# Issue #80 — Data Access Advanced README 강화

**Date**: 2026-05-24
**Issue**: #80 — Data Access Advanced 예제 강화

## 요약

6개 data access 모듈 README를 bluetape4k feature table, Before/After code
comparison, Mermaid architecture diagram으로 강화했다.

## Scope 조정(중요)

원래 이슈는 **Issue #97에서 삭제된** 4개 모듈(`dao-web-transaction`,
`spring-transaction`, `sql-web-virtualthread`, `sql-webflux-coroutines`)을 포함해
8개 모듈을 나열했다. 이 모듈들은 `mvc-jdbc`, `mvc-virtualthread`,
`webflux-r2dbc`로 대체되었다.

**실제 scope(6개 모듈):**

| Module | Action |
|--------|--------|
| `exposed/mvc-jdbc` | Added Mermaid `sequenceDiagram` (already had feature table + before/after from #97) |
| `exposed/mvc-virtualthread` | Added Mermaid, enhanced feature table with code locations, added Before/After |
| `exposed/webflux-r2dbc` | Added Mermaid, enhanced feature table with code locations, added Before/After |
| `spring-data/r2dbc-coroutines` | Added English title, added Mermaid (already had full bt feature table + before/after) |
| `spring-data/r2dbc-webflux` | Added Mermaid, added full bt feature table, added Before/After |
| `spring-data/r2dbc-webflux-exposed` | Added Mermaid, added full bt feature table, added Before/After |
| `exposed/javers-audit` | Skipped — already complete from Issue #100 |

## 문서화한 주요 pattern

### `R2dbcRepository` (bluetape4k-exposed-r2dbc)

`spring-data/r2dbc-webflux-exposed`는 `R2dbcRepository<ID, Entity>`를 base class로
사용해 `findAll()`, `findById()`, `count()`, `deleteById()`를 그대로 얻는다.
custom operation(`upsert`, `findByEmail`)만 구현하면 된다.

### `*Suspending` extensions (bluetape4k-spring-boot4-r2dbc)

`spring-data/r2dbc-coroutines`는 Mono/Flux operation을 suspend function으로 변환하는
`R2dbcEntityOperations.*Suspending` wrapper를 사용해 `awaitSingle()` chain을 제거한다.

### `virtualFuture` + `ShutdownQueue` (bluetape4k-virtualthread-api)

`exposed/mvc-virtualthread`는 `@Transactional` 대신 모든 blocking JDBC 작업을
`virtualFuture(executor) { }`로 제출해 carrier thread pinning을 피한다.
`ShutdownQueue.register(executor)`는 boilerplate 없는 graceful shutdown을 제공한다.

### `suspendTransaction` (bluetape4k-exposed)

`exposed/webflux-r2dbc`는 모든 Exposed R2DBC operation을 `suspendTransaction(db)`로
감싸 coroutine-safe transaction boundary와 Flow stream compatibility를 보장한다.

## 교훈

- 새 이슈 scope에 언급된 모듈이 이전 이슈(예: #97)에서 이미 삭제/대체되었는지
  항상 확인한다. 작성 전에 `git log --all --stat -- <path>`로 검증한다.
- `exposed/javers-audit`는 Issue #100에서 이미 잘 문서화되었으므로 중복 작업을 피한다.
- `spring-data/r2dbc-webflux`는 `CoroutineCrudRepository`를 직접 사용했다. bluetape4k
  repository base class는 없지만 `KLoggingChannel`과 coroutine-first service layer는
  여전히 bluetape4k 사용으로 계산한다.
- 계층형 HTTP→Service→Repo→DB flow에는 Mermaid `sequenceDiagram`이 가장 명확하다.
