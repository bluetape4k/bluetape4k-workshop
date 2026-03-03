# R2DBC + WebFlux + Exposed ORM

Spring Data R2DBC와 Spring WebFlux, JetBrains Exposed ORM을 함께 사용하는 예제입니다.
`bluetape4k-exposed-r2dbc` 모듈을 활용해 Exposed 테이블 정의를 R2DBC 환경에서 사용합니다.

## 구성

| 클래스 | 역할 |
|---|---|
| `UserSchema` | Exposed Table 정의 (`Users` 테이블) |
| `UserExposedRepository` | Exposed R2DBC DSL로 구현한 Repository |
| `UserService` | 비즈니스 로직 (suspend 함수) |
| `UserController` | `@RestController` 어노테이션 방식 REST API |
| `UserHandler` | WebFlux 함수형 라우터 방식 Handler |
| `ExposedR2dbcConfig` | R2DBC + Exposed 연동 설정 |
| `SchemaInitializer` | 애플리케이션 시작 시 스키마 자동 생성 |

## Exposed R2DBC 사용 패턴

```kotlin
// Exposed Table 정의
object Users : LongIdTable("users") {
    val name = varchar("name", 255)
    val email = varchar("email", 255).uniqueIndex()
}

// Repository에서 R2DBC 트랜잭션 안에서 Exposed DSL 사용
suspend fun findById(id: Long): User? = suspendedTransaction {
    Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUser()
}
```

## REST API

| Method | 경로 | 설명 |
|---|---|---|
| GET | `/users` | 전체 사용자 목록 |
| GET | `/users/{id}` | 사용자 조회 |
| POST | `/users` | 사용자 생성 |
| DELETE | `/users/{id}` | 사용자 삭제 |

## 실행

```bash
./gradlew :spring-data-r2dbc-webflux-exposed:bootRun
```

## 참고

- [POC WebFlux-R2DBC H2-Kotlin](https://github.com/razvn/webflux-r2dbc-kotlin)
- [Bluetape4k Exposed R2DBC 모듈](https://github.com/bluetape4k/bluetape4k-projects)
