# R2DBC WebFlux H2 Test 재활성화(Issue #120)

**Date**: 2026-05-24  
**Branch**: fix/issue-120-r2dbc-webflux-tests  
**Module**: `spring-data/r2dbc-webflux`

## 근본 원인

세 test class(`UserServiceTest`, `UserHandlerIT`, `UserControllerTest`)는 H2 2.x SQL
compatibility failure 때문에 `@Disabled` 처리되어 있었다. 기존 `spring.sql.init`
접근은 Spring Boot 4의 R2DBC embedded database에서 신뢰할 수 없었다.

세 가지 문제가 함께 겹쳐 있었다.

### 문제 1: `spring.sql.init`이 schema/data SQL을 실행하지 않음

`spring.sql.init.schema-locations` / `data-locations`는 Spring Boot 4의 R2DBC
embedded database에서 안정적으로 실행되지 않는다. schema가 생성되지 않아 모든
테스트가 "Table not found" 오류로 실패했다.

**수정**: `CompositeDatabasePopulator`로 schema.sql과 data.sql을 순서대로 실행하는
명시적 `ConnectionFactoryInitializer` bean으로 교체한다.

```kotlin
@Bean
fun databaseInitializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer {
    return ConnectionFactoryInitializer().apply {
        setConnectionFactory(connectionFactory)
        setDatabasePopulator(
            CompositeDatabasePopulator().apply {
                addPopulators(ResourceDatabasePopulator(ClassPathResource("data/schema.sql")))
                addPopulators(ResourceDatabasePopulator(ClassPathResource("data/data.sql")))
            }
        )
    }
}
```

### 문제 2: data.sql의 MySQL backtick quoting

H2 2.x는 `MODE=MySQL` 없이 MySQL backtick identifier quoting을 지원하지 않는다.
기존 `data.sql`은 `` INSERT INTO users(`name`, `login`, ...) ``를 사용했다.

**수정**: backtick을 제거하고 plain unquoted column name을 사용한다.

```sql
-- Before (fails on H2 2.x without MODE=MySQL):
INSERT INTO users(`name`, `login`, `email`, `avatar`) VALUES ...

-- After:
INSERT INTO users(name, login, email, avatar) VALUES ...
```

### 문제 3: Spring Data R2DBC H2 dialect identifier 대소문자 불일치

Spring Data R2DBC H2 dialect는 column name을 uppercase(`"ID"`, `"NAME"`, `"EMAIL"`)로
quote하는 반면 `@Table("users")`는 lowercase `"users"`를 생성한다. H2 2.x는
unquoted identifier를 uppercase(`USERS`)로 저장하므로 `"users"` ≠ `USERS`가 된다.

**수정**: R2DBC URL에 `CASE_INSENSITIVE_IDENTIFIERS=TRUE`를 추가한다. 이렇게 하면
H2가 모든 identifier를 case-insensitive하게 비교하므로 Spring Data의 quoted uppercase
column과 schema의 unquoted lowercase definition 간 불일치가 해결된다.

```yaml
spring:
  r2dbc:
    url: r2dbc:h2:mem:///pocdb?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
```

### 문제 4(추가): update test의 `.bodyValue()` 누락

`UserControllerTest.update non-existing user`는 request body 없이 PUT request를
보냈다. 그래서 Spring은 기대한 404가 아니라 `@RequestBody` 누락으로 400을 반환했다.

**수정**: request chain에 `.bodyValue(userToUpdate)`를 추가한다.

## 결과

수정 후 44/44 테스트가 통과했다. 모든 `@Disabled` annotation을 제거했다.

## 결정 기록

- `DATABASE_TO_UPPER=FALSE` 대신 `CASE_INSENSITIVE_IDENTIFIERS=TRUE`를 선택했다.
  전자는 Spring Data가 `@Query` 결과에서 uppercase column alias를 사용하는 것을
  막기 때문이다.
- `spring.sql.init` 대신 `ConnectionFactoryInitializer`를 선택했다. Spring context
  lifecycle에서 명시적으로 순서가 잡히며 test setup 전에 실행됨이 보장되기 때문이다.

## 향후 지침

새 Spring Data R2DBC + H2 in-memory test module을 설정할 때는 다음을 따른다.

1. schema/data initialization에는 `spring.sql.init`이 아니라
   `ConnectionFactoryInitializer` bean을 사용한다.
2. H2 R2DBC URL에 `CASE_INSENSITIVE_IDENTIFIERS=TRUE`를 추가한다.
3. `MODE=MySQL`이 설정되지 않았다면 SQL file에서 MySQL backtick quoting을 피한다.
4. lowercase column name을 사용하는 `@Query`가 CASE_INSENSITIVE mode에서 여전히
   resolve되는지 검증한다.
