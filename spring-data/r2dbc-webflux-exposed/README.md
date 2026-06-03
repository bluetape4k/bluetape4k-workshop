# R2DBC + WebFlux + Exposed ORM

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **R2DBC + WebFlux + Exposed ORM** as a runnable Spring Data persistence workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `spring-data-r2dbc-webflux-exposed`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

![r2dbc-webflux-exposed sequence diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-exposed-readme-sequence-01.png)

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Spring Data R2DBC + Spring WebFlux + JetBrains Exposed ORM, using **bluetape4k `R2dbcRepository`**
for a coroutine-first data access layer. Exposed table DSL handles schema definition; Spring WebFlux
(functional + annotation routes) handles HTTP.

## Architecture

![R2DBC + WebFlux + Exposed ORM Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-exposed-readme-architecture-01.png)

## Architecture Diagram

This example uses Spring Data R2DBC, Spring WebFlux, and JetBrains Exposed ORM together.
It uses the `exposed-r2dbc` module to apply Exposed table definitions in an R2DBC environment.

## Used bluetape4k Features

| Feature | Artifact | Code location | Benefit |
|---------|----------|---------------|---------|
| `R2dbcRepository<ID, Entity>` | `bluetape4k-exposed-r2dbc` | `UserExposedRepository.kt` | Abstract base providing `findAll()`, `findById()`, `count()`, `deleteById()` via Exposed DSL |
| `KLoggingChannel` | `bluetape4k-logging` | `ExposedR2dbcConfig.kt`, `UserService.kt` | Coroutine-aware structured logging |
| `bluetape4k-coroutines` | `bluetape4k-coroutines` | Service layer | Coroutine scope helpers |
| `Runtimex.availableProcessors` | `bluetape4k-core` | `ExposedR2dbcConfig.kt` | CPU-aware connection pool sizing |

## bluetape4k Before / After

### Exposed R2DBC repository with `R2dbcRepository`

```kotlin
// Before — manual Exposed R2DBC CRUD (repeated per entity)
class UserRepository {
    suspend fun findAll(): List<UserRecord> = suspendedTransaction {
        UserTable.selectAll().map { it.toUserRecord() }
    }
    suspend fun findById(id: Int): UserRecord? = suspendedTransaction {
        UserTable.selectAll().where { UserTable.id eq id }.singleOrNull()?.toUserRecord()
    }
    suspend fun deleteById(id: Int): Int = suspendedTransaction {
        UserTable.deleteWhere { UserTable.id eq id }
    }
    // count(), existsById(), findPage()... each written manually
}

// After — bluetape4k R2dbcRepository: inherit CRUD, implement only what's custom
@Repository
class UserExposedRepository : R2dbcRepository<Int, UserRecord> {
    override val table: UserTable = UserTable
    override fun extractId(entity: UserRecord): Int = entity.id
    override suspend fun ResultRow.toEntity(): UserRecord = toUserRecord()

    // Custom operations only — all standard CRUD is inherited
    suspend fun upsert(user: UserRecord) {
        table.upsert(where = { table.id eq user.id }) {
            it[table.name] = user.name
            it[table.email] = user.email
        }
    }
}
```

### R2DBC connection pool with `Runtimex`

```kotlin
// Before — hardcoded pool size
.maxSize(50)

// After — bluetape4k Runtimex: CPU-adaptive sizing
.maxSize(max(Runtimex.availableProcessors * 8, 100))
```

## Components

| Class | Role |
|---|---|
| `UserSchema` | Exposed table definition (`Users` table) |
| `UserExposedRepository` | Repository implemented with the Exposed R2DBC DSL |
| `UserService` | Business logic (suspend functions) |
| `UserController` | `@RestController` annotation-style REST API |
| `UserHandler` | WebFlux functional-router style handler |
| `ExposedR2dbcConfig` | R2DBC + Exposed integration configuration |
| `SchemaInitializer` | Automatic schema creation at application startup |

## Exposed R2DBC Usage Pattern

```kotlin
// Exposed table definition
object Users : LongIdTable("users") {
    val name = varchar("name", 255)
    val email = varchar("email", 255).uniqueIndex()
}

// Use the Exposed DSL inside an R2DBC transaction in the repository
suspend fun findById(id: Long): User? = suspendedTransaction {
    Users.selectAll().where { Users.id eq id }.singleOrNull()?.toUser()
}
```

## REST API

| Method | Path | Description |
|---|---|---|
| GET | `/users` | List all users |
| GET | `/users/{id}` | Fetch a user |
| POST | `/users` | Create a user |
| DELETE | `/users/{id}` | Delete a user |

## Run

```bash
./gradlew :spring-data-r2dbc-webflux-exposed:bootRun
```

## References

- [POC WebFlux-R2DBC H2-Kotlin](https://github.com/razvn/webflux-r2dbc-kotlin)
- [Bluetape4k Exposed R2DBC module](https://github.com/bluetape4k/bluetape4k-projects)
