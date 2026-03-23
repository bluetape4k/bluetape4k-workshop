# Exposed Dao Web Transaction Example

This Spring Boot 3 based project uses Exposed for CRUD (Create, Read, Update, Delete) operations.

## 트랜잭션 처리 흐름

```mermaid
sequenceDiagram
    participant 클라이언트
    participant UserController
    participant UserService
    participant UserTable as Exposed DAO (UserTable)
    participant DB as 데이터베이스 (H2)

    클라이언트->>UserController: POST /users (UserCreateRequest)
    UserController->>UserService: create(request)
    Note over UserService: @Transactional 시작
    UserService->>UserTable: insert { name, age }
    UserTable->>DB: INSERT INTO users ...
    DB-->>UserTable: generated UserId
    UserTable-->>UserService: UserEntity
    UserService-->>UserController: UserCreateResponse
    Note over UserService: @Transactional 커밋
    UserController-->>클라이언트: 201 Created

    클라이언트->>UserController: GET /users/{id}
    UserController->>UserService: findById(id)
    Note over UserService: @Transactional(readOnly=true)
    UserService->>UserTable: select where id = ?
    UserTable->>DB: SELECT * FROM users WHERE id = ?
    DB-->>UserTable: ResultRow
    UserTable-->>UserService: UserDTO
    UserService-->>UserController: UserDTO
    UserController-->>클라이언트: 200 OK (JSON)
```

## 도메인 모델

```mermaid
classDiagram
    class User {
        +UserId id
        +String name
        +Int age
    }
    class UserDTO {
        +Long id
        +String name
        +Int age
    }
    class UserCreateRequest {
        +String name
        +Int age
    }
    class UserCreateResponse {
        +Long id
    }
    class UserUpdateRequest {
        +String name
        +Int age
    }

    UserCreateRequest ..> User : 생성
    User ..> UserDTO : 변환
    User ..> UserCreateResponse : 응답
    UserUpdateRequest ..> User : 수정
```

- [UserEntity.kt](src/main/kotlin/domain/UserEntity.kt): Describes our database schema. If you need to modify the
  structure, please take care to
  understand the existing design first.
- [UserService.kt](src/main/kotlin/service/UserService.kt): Handles CRUD operations for user domains. This class
  determines transaction boundaries via @Transactional,
  fetches data via Exposed DSL, and handles Domain objects.
- [UserController.kt](src/main/kotlin/controller/UserController.kt): Defines various endpoints that handles CRUD and
  calls UserService to process requests.
- [SchemaInitializer.kt](src/main/kotlin/support/SchemaInitialize.kt): Initialize the Database Schema when application
  is run because the sample project uses h2.
- [SpringApplication.kt](src/main/kotlin/SpringApplication.kt): Define Beans and import Configuration class. Import
  ExposedAutoConfiguration in this file.

## Running

To run the sample, execute the following command in a repository's root directory:

```bash
./gradlew bootRun
```
