# H2 2.4.x Compatibility 검증(Issue #146)

**Date**: 2026-05-24  
**Branch**: fix/issue-146-h2-compat  
**Scope**: `vertx/vertx-sqlclient`, `vertx/coroutines`, `redis/redisson-examples`, `spring-boot/chaos-monkey`

## 근본 원인(원 이슈)

H2 2.x는 H2 1.x보다 더 엄격한 SQL compatibility를 도입했다.

- unquoted identifier는 기본적으로 UPPERCASE로 저장되고 비교된다.
- MySQL backtick quoting(`` `column_name` ``)은 `MODE=MySQL` 없이는 지원되지 않는다.
- `AUTO_INCREMENT`는 여전히 동작한다(MySQL compatibility option).

## 발견 사항: 모듈은 이미 compatible했다

검증 결과 영향받는 모든 모듈은 이미 올바른 H2 2.x 설정을 갖고 있었다.

| Module | H2 URL / Settings | Status |
|--------|-------------------|--------|
| `vertx/vertx-sqlclient` | `MODE=MYSQL;DATABASE_TO_UPPER=FALSE` | ✅ Already fixed |
| `vertx/coroutines` | `MODE=MySQL` | ✅ Already fixed |
| `redis/redisson-examples` (JdbcConfigTest) | Spring-managed H2 URL, standard SQL | ✅ Passes |
| `spring-boot/chaos-monkey` | `jdbc:h2:mem:testdb` (JPA auto DDL) | ✅ Passes |

## 기존 Fory Failure(이슈와 무관)

`redis-redisson-examples`에서는 다음 오류로 38개 테스트가 실패했다.
```
java.lang.NoSuchMethodError: 'org.apache.fory.ThreadSafeFory
  org.apache.fory.config.ForyBuilder.buildThreadSafeForyPool(int, int, long, java.util.concurrent.TimeUnit)'
```

**근본 원인**: `fix/issue-146-h2-compat` worktree는 develop이
`bluetape4k = "1.8.0-SNAPSHOT"`을 사용하던 시점에 생성되었다. 이후 develop은
`ForyBuilder` API가 변경된 `1.9.1`로 이동했다. 이 failure들은 H2 2.4.x와
관련이 없다.

## 올바른 H2 2.x URL Pattern

```kotlin
// vertx-sqlclient style: MySQL mode + case-insensitive
jdbcUrl = "jdbc:h2:mem:test;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=FALSE;"

// R2DBC style: case-insensitive identifiers (fixes Spring Data R2DBC quoting)
url: r2dbc:h2:mem:///pocdb?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE

// JPA / JDBC style: H2 auto-handles DDL, no extra options needed for simple schemas
spring.datasource.url=jdbc:h2:mem:testdb
```

## 핵심 결정

R2DBC에서는 `DATABASE_TO_UPPER=FALSE`보다 `CASE_INSENSITIVE_IDENTIFIERS=TRUE`를
선호한다. Spring Data R2DBC H2 dialect는 column name을 uppercase(`"ID"`, `"NAME"`)로
quote하는 반면 `@Table("users")`는 lowercase `"users"`를 만든다. case-insensitive
mode는 schema DDL 변경 없이 두 경우를 모두 해결한다.

## 향후 지침

- Spring Data R2DBC로 H2 in-memory DB를 사용하는 새 모듈을 만들면 R2DBC URL에
  `CASE_INSENSITIVE_IDENTIFIERS=TRUE`를 추가한다.
- Vert.x SQL client 또는 JDBC에서 H2를 사용할 때는 SQL dialect에 따라
  `MODE=MySQL` 또는 `DATABASE_TO_UPPER=FALSE`를 사용한다.
- `MODE=MySQL` 없이 MySQL backtick quoting(`` `col` ``)을 사용하지 않는다.
