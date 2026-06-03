# Jackson Examples

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Jackson Examples**를 실행 가능한 JSON serialization 워크플로우 워크샵 조각으로 다룹니다. 개발자가 가장 먼저 확인할 흐름인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리/프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Jackson Examples Graphviz architecture diagram](../../docs/images/readme-diagrams/json-jackson-examples-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. 이 README를 코드와 비교할 때는 `io.bluetape4k.workshop.json` 패키지를 기준으로 삼습니다.

![Jackson Examples architecture diagram](../../docs/images/readme-diagrams/json-jackson-examples-diagram-01.png)

## 흐름 다이어그램

1. `json-jackson-examples`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, 트레이스 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여 줍니다. 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

Jackson 3.x 라이브러리를 사용해 JSON 데이터를 Java 객체로 변환하거나 Java 객체를 JSON 데이터로 변환하는 방법을 설명합니다.
bluetape4k의 `Jackson.defaultJsonMapper`는 KotlinModules와 JavaTimeModules를 자동 등록하므로 Kotlin data class와 Java Time API를 바로 직렬화할 수 있습니다.

![Jackson Examples diagram](../../docs/images/readme-diagrams/json-jackson-examples-diagram-01.png)

## 사용한 bluetape4k 기능

| function | artifact | code location | advantage |
|---|---|---|---|
| `Jackson.defaultJsonMapper` | `bluetape4k-jackson3` | `AbstractJacksonTest` | KotlinModule + JavaTimeModule + Blackbird 사전 등록 — 별도 설정 불필요 |
| `KLogging` | `bluetape4k-logging` | companion object | 지연 Lambda Logging (`log.debug { "..." }`) |
| `Fakers.faker` | `bluetape4k-junit5` | `AbstractJacksonTest` | JavaFaker 기반 테스트 데이터 생성 |

## Before / After

```kotlin
// Before — Manually register KotlinModule and JavaTimeModule
val mapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .addModule(JavaTimeModule())
    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    .build()

// After — bluetape4k Jackson.defaultJsonMapper (already registered)
val mapper: JsonMapper = Jackson.defaultJsonMapper
    .rebuild()
    .apply { configure(SerializationFeature.INDENT_OUTPUT, true) }
    .build()
```

## 주요 기능

- **Serialization / Deserialization** — `ObjectMapper.writeValueAsString()` / `readValue()`를 사용한 기본 변환
- **Field Control** — `@JsonIgnore`, `@JsonProperty`로 필드 노출과 이름 제어
- **Flattening Nested Objects** — `@JsonUnwrapped(prefix = "...")`로 중첩 객체를 단일 레벨로 펼치기
- **Polymorphism handling** — `@JsonTypeInfo` + `@JsonSubTypes`로 상속 계층 직렬화
- **Insert raw JSON** — `@JsonRawValue`로 JSON 문자열을 그대로 삽입
- **View-based filtering** — `@JsonView`로 응답 context에 따라 필드 선택 노출
- **Date/Time Serialization** — `JavaTimeModule` + `@JsonFormat`으로 Java Time API 지원
- **Circular reference handling** — `@JsonManagedReference` / `@JsonBackReference`로 양방향 관계 처리
- **Dynamic properties** — `@JsonAnySetter` / `@JsonAnyGetter`로 임의 key-value 처리
- **Root Wrapping** — `@JsonRootValue`로 최상위 객체 이름 wrapping
- **One-way serialization** — `@JsonIgnoreProperties(allowGetters = true)` 패턴 활용

## 핵심 annotation

| annotation | location | explanation |
|---|---|---|
| `@JsonIgnore` | field | serialization/deserialization에서 필드 제외 |
| `@JsonProperty("name")` | field | JSON key 이름을 지정한 값으로 변경 |
| `@JsonUnwrapped(prefix = "p_")` | field | 중첩 객체 필드를 상위 레벨로 flatten |
| `@JsonView(Views.Public::class)` | field | 특정 view에서만 필드 노출 |
| `@JsonTypeInfo` | class | JSON에 polymorphic type 정보 포함 |
| `@JsonSubTypes` | class | polymorphic subtype 목록 등록 |
| `@JsonRawValue` | field | 필드 값을 JSON 문자열로 그대로 출력 |
| `@JsonRootValue` | class | 최상위 객체를 class 이름으로 wrapping |
| `@JsonManagedReference` | field | 순환 참조에서 serialization 방향 지정(parent side) |
| `@JsonBackReference` | field | 순환 참조의 reverse(child side, serialized 안 됨) |
| `@JsonAnySetter` | method | JSON의 알 수 없는 key를 동적으로 수신 |
| `@JsonAnyGetter` | method | Map property를 JSON root level로 펼쳐 serialize |
| `@JsonFormat(pattern = "...")` | field | date/time format 사용자 정의 |

## 사용 예제

### ObjectMapper 설정

```kotlin
// bluetape4k Jackson default settings (including KotlinModule + JavaTimeModule)
val mapper: JsonMapper = Jackson.defaultJsonMapper
    .rebuild()
    .apply {
        configure(SerializationFeature.INDENT_OUTPUT, true)
    }
    .build()
```

### Serialization (object → JSON)

```kotlin
data class Friend(
    val name: String,
    @JsonIgnore
    val secret: String? = null,
)

val friend = Friend("Alice", "secret value")
val json = mapper.writeValueAsString(friend)
// Result: {"name":"Alice"} — excluding secret field
```

### Deserialize (JSON → Object)

```kotlin
val json = """{"name":"Alice","secret":"ignored"}"""
val friend = mapper.readValue<Friend>(json)
// friend.secret == null — also exclude deserialization with @JsonIgnore
```

### @JsonUnwrapped — 중첩 객체 flatten

```kotlin
data class Address(val street: String?, val number: Int?)

class Person {
    var name: String? = null

    @get:JsonUnwrapped(prefix = "mainAddress_")
    var mainAddress: Address? = null
}

// Serialization results:
// {"name":"John","mainAddress_street":"Main Street","mainAddress_number":100}
```

### @JsonTypeInfo — 다형성 처리

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = Student::class, name = "student"),
    JsonSubTypes.Type(value = Employee::class, name = "employee"),
)
open class Person(var name: String)

// Serialized result: {"type":"student","name":"Bob","school":"Seoul Univ"}
```

### @JsonFormat — Date/time custom format

```kotlin
class Report {
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy")
    var reportDate: LocalDate? = null

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy|HH:mm|XXX")
    var createdAt: ZonedDateTime? = null
}

// reportDate serialization result: "01/01/2024"
// createdAt serialization result: "01/01/2024 | 14:30 | +09:00"
```

## Kotlin module

Jackson 3.x에서 Kotlin data class를 올바르게 deserialize하려면 `KotlinModule`이 필요합니다.
`bluetape4k-jackson3`는 `Jackson.defaultJsonMapper`에 `KotlinModule`과 `JavaTimeModule`을 사전 등록합니다.

```kotlin
// When setting directly
val mapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .addModule(JavaTimeModule())
    .build()
```

`jackson-module-blackbird`는 reflection 기반 accessor를 bytecode generation으로 대체해 성능을 향상합니다.

```kotlin
// build.gradle.kts
implementation(Libs.jackson3_module_blackbird)
```

## 테스트 목록

| test file | What we cover |
|---|---|
| `IgnoreExample` | `@JsonIgnore` Serialization/Deserialization |
| `JsonUnwrappedExample` | `@JsonUnwrapped` Flattening |
| `JsonViewExample` | `@JsonView` View-based filtering |
| `PolymorphismExample` | `@JsonTypeInfo` / `@JsonSubTypes` polymorphism |
| `RawValueExample` | `@JsonRawValue` Insert raw JSON |
| `RenameExample` | `@JsonProperty` Change field name |
| `JavaTimeExample` | `@JsonFormat` Date/Time Format Conversion |
| `CyclicExample` | `@JsonManagedReference` / `@JsonBackReference` circular reference |
| `DynamicAttributeExample` | `@JsonAnySetter` / `@JsonAnyGetter` Dynamic properties |
| `OnewayExample` | One-way serialization |
| `RootValueExample` | `@JsonRootValue` Root Wrapping |
| `SimpleExamples` | Basic serialization/deserialization example |

## 참고 자료

- [Jackson Official Document](https://github.com/FasterXML/jackson)
- [Jackson Annotations Wiki](https://github.com/FasterXML/jackson-annotations/wiki/Jackson-Annotations)
- [jackson-module-kotlin](https://github.com/FasterXML/jackson-module-kotlin)
- [Jackson JavaTimeModule](https://github.com/FasterXML/jackson-modules-java8)
