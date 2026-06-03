# Module Protobuf in Spring Boot MVC

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Module Protobuf in Spring Boot MVC**를 실행 가능한 Spring Boot 애플리케이션 기능 워크숍 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Module Protobuf in Spring Boot MVC Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-boot-protobuf-mvc-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springboot` 패키지를 기준으로 삼습니다.

## 흐름 다이어그램

1. `spring-boot-protobuf-mvc`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크숍 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여 줍니다. 없으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![Module Protobuf in Spring Boot MVC sequence diagram](../../docs/images/readme-diagrams/spring-boot-protobuf-mvc-sequence-01.png)

이 예제는 서버 데이터를 Protobuf 형식으로 보내고 받는 방법을 보여 줍니다.

## Protobuf 직렬화 흐름

![Protobuf diagram](../../docs/images/readme-diagrams/spring-boot-protobuf-mvc-sequence-01.png)

## Protobuf 개념

**Protocol Buffers(Protobuf)**는 Google이 개발한 언어 및 플랫폼 중립 바이너리 직렬화 형식입니다.

| 기능 | JSON | Protobuf |
|------|------|----------|
| 형식 | 텍스트 | 바이너리 |
| 스키마 | 선택 사항 | `.proto` 파일로 강제 |
| 페이로드 크기 | 상대적으로 큼 | 3-10배 더 작음 |
| Content-Type | `application/json` | `application/x-protobuf` |
| 코드 생성 | 필요 없음 | `protoc` 컴파일러로 자동 생성 |

Spring Boot에서는 `ProtobufHttpMessageConverter` 빈을 등록하면 `application/x-protobuf` Content-Type 요청과 응답을 자동으로 처리합니다.

## 도메인 모델(`.proto` 스키마)

```protobuf
// school.proto
message Course {
  int32 id = 1;
  string course_name = 2;
  repeated Student student = 3;
}

message Student {
  int32 id = 1;
  string first_name = 2;
  string last_name = 3;
  string email = 4;
  repeated PhoneNumber phone = 5;

  enum PhoneType { MOBILE = 0; LANDLINE = 1; }
  message PhoneNumber {
    string number = 1;
    PhoneType type = 2;
  }
}
```

Kotlin DSL 빌더(`course { }`, `student { }`, `phoneNumber { }`)로 Protobuf 메시지를 간결하게 만들 수 있습니다.

## 주요 기능

| 기능 | 구현 위치 | 설명 |
|------|----------|------|
| Protobuf 컨버터 등록 | `ConurceConfig` | `ProtobufHttpMessageConverter` 빈 등록 |
| Course 조회 | `CourseController.course()` | `GET /courses/{id}` — Protobuf 직렬화 응답 |
| JSON 변환 유틸리티 | `ProtobufConverter` | `MessageOrBuilder.toJson()`, `messageFromJsonOrNull<T>()` |
| 인메모리 저장소 | `CourseRepository` | `Map<Int, Course>` 기반 단순 저장소 |

## API 엔드포인트

| 메서드 | 경로 | 설명 | Content-Type |
|--------|------|------|-------------|
| `GET` | `/courses/{id}` | 특정 course를 조회합니다. | `application/x-protobuf` |

## 사용 예제

### Protobuf 요청(curl)

```bash
# Receive a Protobuf response (binary)
curl -H "Accept: application/x-protobuf" http://localhost:8080/courses/1 -o course.pb

# JSON can also be received through Spring Content Negotiation
curl -H "Accept: application/json" http://localhost:8080/courses/1
```

### Kotlin DSL로 Protobuf 메시지 만들기

```kotlin
val newCourse = course {
    id = 1
    courseName = "Kotlin Programming"
    student.addAll(listOf(
        student {
            id = 1
            firstName = "John"
            lastName = "Doe"
            email = "john.doe@example.com"
            phone.add(phoneNumber {
                number = "010-1234-5678"
                type = Student.PhoneType.MOBILE
            })
        }
    ))
}
```

### Protobuf와 JSON 상호 변환

```kotlin
// Convert a Protobuf message to a JSON string
val json: String = course.toJson()

// Convert a JSON string to a Protobuf message
val course: Course? = messageFromJsonOrNull<Course>(json)
```

## 빌드 설정

`.proto` 파일은 `src/main/proto/` 아래에 있으며, `protobuf-gradle-plugin`이 빌드 중 Kotlin/Java 소스를 자동 생성합니다.

```kotlin
// build.gradle.kts
plugins {
    id("com.google.protobuf") version "..."
}
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:..." }
    generateProtoTasks { all().forEach { it.builtins { id("kotlin") } } }
}
```
