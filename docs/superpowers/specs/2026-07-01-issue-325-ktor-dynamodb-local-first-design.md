# Issue #325 - Ktor DynamoDB 지역 우선 워크샵 사양

- 날짜: 2026-07-01
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/325
- 작업 유형: A형 전체 기능
- 대상 저장소: `bluetape4k/bluetape4k-workshop`
- 대상 모듈: `aws/ktor-dynamodb`
- Gradle 프로젝트: `:aws-ktor-dynamodb`

## 문제

`bluetape4k-aws 0.4.0`이 Ktor DynamoDB 통합을 추가했지만
`bluetape4k-workshop`에는 Ktor,
Kotlin DynamoDB용 AWS SDK 및 로컬 AWS 호환 에뮬레이터. 기존의
워크샵 모듈은 Ktor REST 기본 사항과 여러 가지 AWS S3 중심 예제를 다루고 있습니다.
하지만 학습자에게는 작은 로컬 우선 DynamoDB REST 애플리케이션이 필요합니다.
이러한 경계는 명시적입니다.

- Ktor 경로는 코루틴 서비스 경계를 ​​호출합니다.
- 서비스는 `DynamoDbKtorPlugin` 및 `DynamoDbKtorRepository`를 사용합니다.
  테이블 부트스트랩, 생성 및 조회 경로.
- 낙관적 업데이트는 `version`에 대해 DynamoDB 조건식을 사용합니다.
  기인하다.
- 조건부 실패, 항목 누락, 유효성 검사 실패가 안전해집니다 HTTP
  JSON 응답.
- 기본 확인은 로컬 emulators/test double만 사용하고 실제 AWS은 사용하지 않습니다.

## 현재 증거

- Issue #325은(는) 마일스톤 `1.3.1`에서 열려 있고 `debop`에 할당되었으며
  Ktor DynamoDB 지역 우선 워크숍 예시.
- Epic #321에서는 모든 하위 이슈가 루트만 사용하도록 요구합니다.
  `bluetape4k-dependencies` BOM, 로컬 결정론적 검증을
  기본 경로를 선택하고 `README.md` 및 `README.ko.md`을 모두 업데이트하세요.
- `settings.gradle.kts`은 `aws/*` 디렉토리를 자동 등록하므로
  수동 포함이 없으면 `aws/ktor-dynamodb`은 `:aws-ktor-dynamodb`이 됩니다.
- `gradle/libs.versions.toml`은(는) 이미 `bluetape4k-dependencies 1.3.1`를 가져옵니다.
  하지만 현재는 `bluetape4k-aws-spring-boot`만 노출합니다.
  `bluetape4k-aws` 별칭. 새 모듈에는 다음에 대한 명시적인 별칭이 필요합니다.
  `bluetape4k-aws-ktor` 및 `bluetape4k-aws-kotlin`(로컬 버전 없음)
  그 이유는 해당 버전이 루트 BOM에 의해 관리되기 때문입니다. 그것은 또한 필요하다
  `aws.sdk.kotlin:dynamodb`을 `version.ref = "aws-kotlin"`과 함께 사용하기 때문에
  `bluetape4k-dependencies`은 AWS Kotlin 버전을 노출하지만 DynamoDB는 노출하지 않습니다.
  서비스 별칭. `software.amazon.awssdk:auth`은(는) 다음과 같은 경우에만 추가되어야 합니다.
  구현에는 AWS Kotlin 경로 외부에 Java SDK v2 인증 도우미가 필요합니다.
  Ktor JSON 지원에서는 `ktor-serialization-kotlinx-json`을 사용해야 합니다.
  기존 워크숍 Ktor 모듈.
- Ktor 공식 문서에서는 `testApplication {}` 및 구성된 테스트를 사용합니다.
  격리된 서버 테스트를 위해 `ContentNegotiation`이 있는 클라이언트.
- AWS SDK for Kotlin 공식 문서는 로컬 엔드포인트 재정의를 지원합니다.
  클라이언트 구성을 통해 `DynamoDbClient.fromEnvironment`을 다음과 같이 표시합니다.
  일반적인 실제-AWS 생성 경로.
- `bluetape4k-aws`은(는) 이미 다음을 제공합니다.
  - `DynamoDbKtorPlugin`(`autoCreateTables` 및 애플리케이션 수명 주기 포함)
    startup/shutdown 후크,
  - `DynamoDbKtorPluginConfig.endpointUrl`, `region` 및
    `credentialsProvider` 로컬 에뮬레이터의 경우,
  - `DynamoDbKtorRuntime.repository(...)`,
  - `DynamoDbKtorRepository.save`, `put`, `findById`, `scan`, `query`,
  - DynamoDB `DynamoItemMapper`과 같은 매퍼 도우미,
    `DynamoItemReader`, `partitionKeyOf`, `stringAttrDefinitionOf`.
- `bluetape4k-aws` 예제에서는 기본적으로 `FlociServer.Launcher.floci`을 사용하고
  `LocalStackServer.Launcher.getLocalStack("dynamodb")`을 선택 에뮬레이터로 사용합니다.
- `Examples.yml`은(는) 이미 용기 없는 연기 예를 순차적 연기 예와 분리합니다.
  컨테이너 지원 예시. 이 모듈은 컨테이너 지원 레인에 속합니다.
- `scripts/smoke-validate.sh stale-check`에서는 현재 94개의 Gradle 프로젝트를 예상하고 있습니다.
  새 모듈에서는 해당 수가 95로 증가합니다.

## 제약

- 루트 `bluetape4k-dependencies` BOM만 사용하세요. bluetape4k 모듈을 고정하지 마십시오.
  버전.
- DynamoDB 경로의 경우 Kotlin DynamoDB에 AWS SDK을 사용하세요. AWS SDK v2를 사용하지 마세요
  이 예의 핵심 흐름에 대한 Java DynamoDB입니다.
- 수동 롤링 Ktor 플러그인 대신 `bluetape4k-aws-ktor` 통합을 사용하세요.
  수명주기 또는 원시 클라이언트 소유권.
- `bluetape4k-testcontainers` AWS 에뮬레이터 실행기를 사용하세요. 인스턴스화하지 않음
  원시 `GenericContainer`.
- 기본 테스트는 로컬 에뮬레이터에 대해 실행되어야 하며 실제 테스트가 필요하지 않아야 합니다.
  AWS 자격 증명, AWS 계정 액세스 또는 라이브 AWS 리소스.
- Production/main 애플리케이션 코드는 Testcontainer를 시작해서는 안 됩니다. 테스트 공급
  에뮬레이터 엔드포인트, 지역 및 자격 증명.
- 실제 AWS 모드에는 다음과 같은 명시적인 옵트인 플래그가 필요합니다.
  `-Dbluetape4k.aws.mode=real`; 기본 모드는 로컬 에뮬레이터 전용입니다.
- Testcontainers이(가) 지원하는 확인 일련번호를 `--max-workers=1`에 보관하고,
  모듈 수준 JUnit 병렬 실행을 비활성화합니다.
- 예시에서는 DynamoDB local-first REST, 조건부 쓰기,
  그리고 낙관적인 업데이트. S3/SQS/SNS 문제를 추가하지 마세요.
- README 작업은 이중 언어로 이루어집니다. `README.md` 및 `README.ko.md`을 모두 업데이트하세요.
  모듈 목록 또는 지침이 변경됩니다.
- 다이어그램 작업은 `$bluetape4k-diagram`을 사용하고, SVG 및 PNG 자산을 생성하고, 통과해야 합니다.
  현재 체크리스트를 확인하고 전체 크기의 육안 검사 증거를 기록합니다.
- 새 모듈 등록은 AWS/root README 테이블을 포함해야 합니다(예: CI).
  적용 범위, 오래된 확인 프로젝트 수 및 `./gradlew projects`.

## 목표

1. `aws/ktor-dynamodb`를 지원하는 로컬 우선 Ktor REST 애플리케이션으로 추가합니다.
   DynamoDB.
2. 로컬 에뮬레이터에 대한 `DynamoDbKtorPlugin` 테이블 부트스트랩을 보여줍니다.
3. 생성, 읽기, 페이지 매김 제한이 있는 작은 `OrderSession` 리소스를 노출합니다.
   목록, 낙관적 업데이트 및 삭제 작업.
4. `attribute_not_exists(#id)`과 함께 조건부 생성을 사용하세요.
5. `attribute_exists(#id) AND #version = :expected`과 함께 낙관적 업데이트를 사용합니다.
6. 중복 생성 및 오래된 버전 업데이트 실패를 `409 Conflict`에 매핑합니다.
7. 누락된 항목 lookup/update/delete를 `404 Not Found`에 매핑합니다.
8. 에뮬레이터 누출 없이 검증 실패를 `400 Bad Request`에 매핑
   엔드포인트 또는 자격 증명.
9. 표지 생성, 업데이트, 중복 조건부 실패, 오래된 버전
   조건부 실패, 조회 누락, 삭제, 제한된 목록, 페이지 매김,
   동시 오래된 업데이트, 잘못된 형식의 JSON, 준비 상태 및 JSON 직렬화
   테스트에서.
10. 로컬 run/test 명령, 에뮬레이터 선택 및 실제 AWS 옵트인을 문서화하세요.
    두 README 로캘 모두에 대한 메모입니다.
11. Ktor 경로를 설명하는 아키텍처 및 시퀀스 다이어그램을 추가합니다.
    서비스, ​​DynamoDB plugin/runtime, 저장소 및 emulator/table 레이어.
12. 정상적인 연기를 내지 않고 CI/container 유효성 검사에 모듈을 등록합니다.
    테스트에는 Docker가 필요합니다.

## 논골

- 기본 테스트나 기본 실행 지침에서는 실제 AWS을 호출하지 마세요.
- IAM 정책 관리, 프로덕션 테이블 마이그레이션, 글로벌을 추가하지 마십시오.
  보조 인덱스, 스트림, 트랜잭션 또는 고급 클라이언트 매퍼.
- 기존 `bluetape4k-aws` 라이브러리 예제를 바꾸지 마십시오. 이번 워크숍
  더 많은 문서와 다이어그램이 포함된 학습자 대상 소비자 예제입니다.
- 이 항목에 Spring Boot, Exposed, R2DBC, Kafka, Redis 또는 S3을 도입하지 마세요.
  기준 치수.
- 구현이 표시되지 않는 한 새로운 공유 추상화 계층을 생성하지 마십시오
  이 모듈 내부에 의미 있는 중복이 있습니다.
- hard Kover 임계값을 복원하거나 관련 없는 CI 정책을 변경하지 마십시오.

## 접근 옵션

### 옵션 A - `aws/ktor-dynamodb` DynamoDB Ktor 플러그인 포함

`DynamoDbKtorPlugin`를 설치하고 등록하는 새 AWS 워크샵 모듈을 만듭니다.
하나의 요청당 지불 DynamoDB 테이블과 전선 Ktor이
`OrderSessionService`. 테스트에서는 기본적으로 Floci를 사용하고 다음 경우에는 LocalStack을 사용합니다.
`-Dbluetape4k.aws.emulator=localstack`이(가) 제공됩니다.

이익:

- 이슈 범위와 직접 일치합니다.
- 게시된 bluetape4k Ktor DynamoDB 통합을 재사용합니다.
- 기본 경로를 로컬 및 결정적으로 유지합니다.
- service/repository 분리와 DynamoDB 조건식을 가르칩니다.
- 기존 AWS 워크숍 모듈 그룹에 적합합니다.

소송 비용:

- Docker/emulator-backed 테스트는 인메모리 테스트보다 느립니다.
- CI은 모듈을 순차 컨테이너 레인에 유지해야 합니다.

### 옵션 B - `bluetape4k-aws` 예제 문서만 확장

기존 `bluetape4k-aws/examples/aws-ktor-dynamodb-examples` 참조
워크샵 모듈을 추가하지 않고 워크샵 README에서 모듈을 추가합니다.

이익:

- 최소한의 코드 변경.

소송 비용:

- 현지 워크숍 테스트에 대한 워크숍 승인 기준을 통과하지 못했습니다.
  학습자 지향 README 패리티 및 다이어그램.
- 이 저장소에서 소비자 종속성 카탈로그를 실행하지 않습니다.

### 옵션 C - Ktor DynamoDB 이중 테스트만 수행

인메모리 가짜 저장소 및 문서 DynamoDB에 대해 Ktor 경로를 구축합니다.
미래의 일로.

이익:

- 가장 빠른 기본 테스트.

소송 비용:

- 로컬에 대해 DynamoDB 테이블을 부트스트랩하기 위한 이슈 요구 사항에 실패합니다.
  에뮬레이터.
- 조건식 동작을 증명하지 않습니다.

## 결정

옵션 A를 사용하세요. 새 모듈은 Ktor + DynamoDB 워크숍 샘플에 중점을 둡니다.
`aws/ktor-dynamodb` 아래, 로컬 AWS 호환 에뮬레이터 테스트와 지원
이중 언어 README 파일과 체크리스트로 검증된 다이어그램으로 문서화됩니다.

## 건축학

### 런타임 구성요소

- `KtorDynamoDbApplication`: Ktor 진입점 및 환경 속성 브리지
  수동 로컬 실행의 경우.
- `DynamoDbLocalConfig`: 엔드포인트, 지역, 자격 증명 및 테이블 이름 옵션
  테스트 또는 수동 실제 AWS 옵트인을 통해 제공됩니다.
- `DynamoDbKtorPlugin`: AWS Kotlin SDK을 생성하거나 수신합니다.
  `DynamoDbClient`, 등록된 테이블을 자동 생성하고 플러그인 소유를 닫습니다.
  애플리케이션 종료 중 클라이언트.
- `OrderSessionRoutes`: Ktor HTTP request/response 처리를 위한 경로 모듈입니다.
- `OrderSessionService`: 유효성 검사를 위한 코루틴 서비스 경계,
  중복 생성, 낙관적 업데이트, 제한된 목록, 조건부 실패
  명확성 및 오류 매핑.
- `OrderSessionDynamoRepository`: 얇은 DynamoDB 어댑터 사용
  create/lookup/list 및 직접 DynamoDB SDK의 경우 `DynamoDbKtorRepository`
  조건부 낙관적 업데이트 및 조건부 삭제가 필요합니다.
- `OrderSession`, 요청 DTO, 응답 DTO 및 오류 DTO:
  `@Serializable`, `java.io.Serializable`, `serialVersionUID`-베어링
  학습자 지향 모델.
- 로컬 AWS 에뮬레이터: Floci는 기본적으로 LocalStack 패리티 검사를 선택합니다.

### 데이터 모델

`OrderSession`은 단일 테이블 기본 키를 사용합니다.

| 속성 | 유형 | 목적 |
| --- | --- | --- |
| `id` | 문자열 파티션 키 | 호출자가 제공한 안정적인 order/session ID입니다. |
| `customerId` | 문자열 | 일반적인 스칼라 속성을 보여줍니다. |
| `status` | 문자열 열거형 값 | 작은 수명 주기 상태: `CREATED`, `APPROVED`, `CANCELLED`. |
| `notes` | 문자열 | 선택적 학습자가 볼 수 있는 세부정보입니다. |
| `version` | 번호 | 업데이트 시 증가되는 낙관적 동시성 토큰입니다. |

이 예에서는 의도적으로 정렬 키와 보조 색인을 피하므로 학습자가
고급 DynamoDB 모델링 이전에 조건부 쓰기에 집중할 수 있습니다.

### 데이터 Flow

1. 학습자가 Ktor에 JSON 요청을 보냅니다.
2. Ktor은 `ContentNegotiation`을 통해 역직렬화합니다.
3. 경로는 `OrderSessionService`에 위임됩니다.
4. 서비스는 의미 필드의 유효성을 검사하고 저장소를 호출합니다.
5. 저장소는 `DynamoDbKtorPlugin` 런타임을 사용하여 테이블에 액세스합니다.
6. Create는 `attribute_not_exists(#id)`과 함께 `PutItem`을 사용합니다.
7. 읽기는 저장소 파사드를 사용합니다.
8. 목록은 기본 제한, 최대 제한,
   불투명 `nextToken`; README는(는) 스캔이 워크샵 전용임을 경고해야 합니다.
   프로덕션 쿼리 디자인이 아닌 액세스 패턴입니다.
9. 업데이트 성공은 다음과 같은 `UpdateItem` 명령 하나입니다.
   `attribute_exists(#id) AND #version = :expected`. 조건부 업데이트인 경우
   실패하면 서비스는 해당 실패 경로에서만 대체`GetItem`를 수행합니다.
   없는 항목은 `404`에 매핑되고, 다른 버전의 항목이 매핑되는 경우
   `409`.
10. 삭제는 `attribute_exists(#id)`과 함께 조건부 `DeleteItem`을 사용합니다. 에이
   조건부 삭제 실패는 `404`에 매핑됩니다.
11. 유효성 검사 실패는 `BadRequest`이 됩니다.
12. `StatusPages`은 안전한 JSON 오류 응답을 직렬화합니다.

### DynamoDB 명령 예산

| 운영 | 명령 예산 | 메모 |
| --- | --- | --- |
| 성공을 창조하다 | 1 `PutItem` | `attribute_not_exists(#id)`를 사용합니다. |
| 중복 생성 | 1개 실패 `PutItem` | `409`에 직접 매핑됩니다. |
| success/miss 읽기 | 1 `GetItem` | Miss는 `404`에 매핑됩니다. |
| 목록 | 페이지당 1개의 경계 `Scan` | 기본 제한 25, 최대 제한 100, 불투명 연속 토큰. |
| 업데이트 성공 | 1 `UpdateItem` | 성공 경로에는 쓰기 전 읽기가 없습니다. |
| 업데이트 실패 | <= 1 실패 `UpdateItem` + 1 `GetItem` | 누락된 `404`와 오래된 `409`을 명확하게 구분합니다. |
| 삭제 성공 | 조건부 1개 `DeleteItem` | `attribute_exists(#id)`를 사용합니다. |
| 미스 삭제 | 조건부 1개 실패 `DeleteItem` | `404`에 매핑됩니다. |
| 준비 | 일반 프로브당 명령 0개 | 시작 시 캡처된 상태를 사용합니다. 선택적 새로 고침은 30초당 최대 1개의 `DescribeTable`을 실행할 수 있습니다. |

도메인 수준 코드는 유효성 검사 실패를 다시 시도해서는 안 됩니다.
`ConditionalCheckFailedException`. 로컬 테스트 AWS SDK 재시도 동작은 다음과 같아야 합니다.
객관적으로 제한됨: 최대 2회 시도, 총 통화 기한 5초,
백오프 한도는 500밀리초입니다.

### HTTP DTO

| DTO | 필드 |
| --- | --- |
| `CreateOrderSessionRequest` | `id: String`, `customerId: String`, `status: OrderSessionStatus = CREATED`, `notes: String = ""` |
| `UpdateOrderSessionRequest` | `expectedVersion: Long`, `status: OrderSessionStatus`, `notes: String = ""` |
| `OrderSessionResponse` | `id: String`, `customerId: String`, `status: OrderSessionStatus`, `notes: String`, `version: Long` |
| `OrderSessionListResponse` | `items: List<OrderSessionResponse>`, `nextToken: String?` |
| `ErrorResponse` | `code: String`, `message: String` |
| `ReadinessResponse` | `status: "UP" | "DOWN"`, `mode: "local" | "real"`, `emulator: "floci" | "localstack" | null`, `region: String`, `tableName: String`, `tableReady: Boolean`, `checkedAt: String` UTC ISO-8601 인스턴트 |

`OrderSessionStatus`은(는) `CREATED`, `APPROVED` 및 `CANCELLED`에 허용 목록에 추가되었습니다.
요청 제어 문자열은 DynamoDB에 연결되어서는 안 됩니다.
표현; 모든 속성 이름은 고정된 별칭이고 모든 사용자 값은
`ExpressionAttributeValues`을 통해 바인딩됩니다.

### HTTP API

| 방법 | 경로 | 행동 |
| --- | --- | --- |
| `POST` | `/dynamodb/order-sessions` | 버전 `1`에서 주문 세션을 생성합니다. 중복 ID는 `409`를 반환합니다. |
| `GET` | `/dynamodb/order-sessions/{id}` | 하나의 주문 세션 또는 `404`를 반환합니다. |
| `GET` | `/dynamodb/order-sessions?limit=25&nextToken=...` | 하나의 제한된 스캔 페이지와 선택적 연속 토큰을 반환합니다. |
| `PUT` | `/dynamodb/order-sessions/{id}` | `expectedVersion`가 일치하면 status/notes를 업데이트합니다. 오래된 버전은 `409`을 반환합니다. |
| `DELETE` | `/dynamodb/order-sessions/{id}` | 조건부 삭제는 `204 No Content`를 반환합니다. 누락된 ID는 `404`을 반환합니다. |
| `GET` | `/health/readiness` | 캐시된 테이블 준비가 정상이면 `200 ReadinessResponse(status="UP")`를 반환합니다. 시작 후 TTL 새로 고침이 실패하거나 테이블이 비활성인 경우에만 `503 ReadinessResponse(status="DOWN")`을 반환합니다. `DynamoDbKtorPlugin` startup/table-bootstrap 실패는 준비 상태를 제공하는 대신 애플리케이션 시작에 실패하도록 허용됩니다. 반복적인 조사는 모든 요청에 ​​대해 DynamoDB 명령을 실행해서는 안 됩니다. |

## 오류 처리

- 검증 실패는 명시적인 필드 검사를 사용하고 안정적인 결과를 반환합니다.
  `ErrorResponse(code, message)`.
- AWS `ConditionalCheckFailedException`은(는) 도메인 충돌로 해석됩니다.
  원시 AWS 요청 ID, 엔드포인트 또는 자격 증명을 유출하지 않고.
- Lookup/update/delete 경로 누락은 `404`을 반환합니다.
- 예기치 않은 DynamoDB 오류가 서비스 경계에 기록됩니다.
  정리된 구조화된 필드만 해당: 작업 이름, 안전한 오류 클래스 및
  진단 ID를 요청하세요. 원시 AWS 예외 메시지, 요청 ID,
  엔드포인트, 헤더, 자격 증명, 전체 request/response 페이로드 또는
  환경 파생 구성.
- 코루틴 `CancellationException`은 광범위한 예외 매핑 전에 다시 발생합니다.
- 잘못된 JSON은(는) 안전한 `400` 응답을 반환합니다.
- 시간 초과 또는 에뮬레이터 부트스트랩 실패는 안전한 `503`/`500` 응답을 반환합니다.
  시작 및 요청 단계에 따라 다르며 엔드포인트를 노출해서는 안 됩니다.
  자격 증명 세부 정보.

## 보안 기본값

- 워크숍 API은(는) 인증되지 않았으며 로컬 우선입니다. README 파일에는 다음이 명시되어야 합니다.
  돌연변이 경로는 외부 없이 공개적으로 노출되어서는 안 됩니다.
  인증, 권한 부여, 네트워크 제어 및 최소 권한 IAM.
- 로컬 에뮬레이터 자격 증명은 테스트 또는 로컬에서 제공되는 정적 더미 값입니다.
  스크립트. 실제 자격 증명은 커밋, 인쇄, 기록 또는 포함되지 않습니다.
  README 예.
- 실제 AWS 옵트인은 AWS default/environment 자격 증명 공급자 체인을 사용합니다.
  `-Dbluetape4k.aws.mode=real`만 필요합니다.
- DTO-JSON 역직렬화가 필요합니다. Jackson 기본값을 활성화하지 마십시오
  타이핑 또는 다형성 유형 처리. Ktor `kotlinx.serialization` JSON 선호
  기존 워크숍 Ktor 모듈과 일치합니다.
- 요청 본문 크기는 Ktor 구성 또는 경로 수준 확인으로 제한되어야 합니다.
  이 모듈에 실용적인 경우.

## 운영

- 시작 로그에는 모드(`local` 또는 `real`), 에뮬레이터(`floci`,
  `localstack` 또는 null), 지역, 테이블 이름 및 삭제된 엔드포인트 클래스
  (`local-emulator` 또는 `aws`)이지만 자격 증명이나 전체 엔드포인트 URL은 아닙니다.
- 모든 오류 응답에는 안정적이고 안전한 메시지가 포함됩니다. 진단
  ID가 반환되거나 기록될 수 있지만 자격 증명이나 원시 AWS가 포함되어서는 안 됩니다.
  ID.
- 준비 상태는 시작 시 테이블 부트스트랩 상태를 캡처하고 다음과 같이 새로 고쳐질 수 있습니다.
  30초 TTL. 반복되는 프로브는 모든 요청에서 DynamoDB을 호출해서는 안 됩니다. 에이
  새로 고침은 최대 하나의 `DescribeTable(tableName)`을 발행할 수 있으며 시간 초과되어야 합니다.
  2초 이내.
- 테이블 부트스트랩은 제한된 `DynamoDbKtorPlugin.autoCreateTables`을 사용합니다.
  테스트에서는 테이블 준비 시간 제한이 30초이고 수동 실행의 경우 60초입니다.
- Local/test 테이블은 일회용입니다. 테스트를 통해 고유한 테이블 이름을 만들고 정리합니다.
  가능하면 올려두세요. 실제 AWS 모드 문서에는 비용과 정리가 포함되어야 합니다.
  경고.
- 스키마 진화는 범위를 벗어납니다. 지역 사례에서는 일회용 테이블 재구축을 사용합니다.
  마이그레이션보다는.

## 계약 구축

- 모듈에 `alias(libs.plugins.kotlin.serialization)`을 적용합니다.
- `implementation(platform(libs.ktor.bom))`를 사용하세요.
- `implementation(libs.ktor.serialization.kotlinx.json)`을 사용하고
  DTO JSON 지원을 위한 `implementation(libs.kotlinx.serialization.json)`.
- `bluetape4k-aws-ktor`, `bluetape4k-aws-kotlin`을 추가하고
  `aws-kotlin-dynamodb` 현재 증거에 설명된 대로 카탈로그 별칭.

## 테스트 전략

- TDD 레드 테스트는 제품 구현에 앞서 진행됩니다.
- Ktor `testApplication {}` 및 JSON 클라이언트를 사용하세요.
  `ContentNegotiation`.
- 기본적으로 `FlociServer.Launcher.floci`를 사용합니다.
  `bluetape4k-testcontainers`.
- 동일한 테스트를 실행할 수 있도록 `-Dbluetape4k.aws.emulator=localstack` 지원
  `LocalStackServer.Launcher.getLocalStack("dynamodb")`.
- 테스트 클래스별로 고유한 테이블 이름과 테스트별로 고유한 항목 ID를 생성합니다.
  에뮬레이터가 지원하는 테스트 소유 tables/items를 정리합니다. 목록
  어설션의 범위는 테스트 소유 ID로 지정되어야 합니다.
- 모듈 테스트 리소스에서 JUnit 병렬 실행을 비활성화합니다.
- 바인딩된 에뮬레이터 시작, 테이블 부트스트랩, 준비 및 경로 테스트
  명시적 시간 초과: 에뮬레이터 시작 시 90초, 테스트 테이블 시 30초
  준비 상태, 준비 상태 새로 고침에 2초, 경로 테스트당 10초.
  녹화 cold/warm `./gradlew :aws-ktor-dynamodb:test --max-workers=1` 타이밍
  마지막 DoD에 증거가 있습니다. 웜 테스트인 경우 문제를 숨기지 말고 에스컬레이션하세요.
  로컬에서 런타임이 3분을 초과하거나 콜드 런타임이 6분을 초과합니다.
  기계.
- 로컬 테스트에 제한된 AWS SDK 재시도 설정 사용: 총 최대 2회 시도
  호출 기한은 5초이고 백오프 한도는 500밀리초입니다. 추가하지 않음
  조건부 실패에 대한 도메인 수준 재시도.
- 씌우다:
  - 하나의 주문 세션을 생성한 후 읽습니다.
  - 제한된 목록에는 생성된 주문 세션과 returns/consumes이 포함됩니다.
    연속 토큰,
  - 중복 생성은 `409 Conflict`을 반환합니다.
  - 일치하는 `expectedVersion` 증분 `version`으로 업데이트합니다.
  - 오래된 `expectedVersion`으로 업데이트하면 `409 Conflict`이 반환됩니다.
  - 동일한 예상 버전의 동시 업데이트는 정확히 하나의 버전을 생성합니다.
    재시도나 스핀 루프 없이 성공하고 나머지는 `409`
  - 조회 누락이 `404 Not Found`를 반환합니다.
  - 삭제는 기존 항목을 제거하고 삭제가 없으면 `404`을 반환합니다.
  - 잘못된 상태 또는 공백 id/customer ID가 `400 Bad Request`을 반환합니다.
  - 잘못된 JSON은(는) `400 Bad Request`를 반환합니다.
  - JSON 직렬화 왕복 요청 및 응답 DTO,
  - 준비 경로는 자동 생성된 테이블을 확인합니다.
- 모듈 테스트를 순차적으로 실행합니다.

```bash
./gradlew :aws-ktor-dynamodb:test --max-workers=1
```

## 문서 및 다이어그램

- 모듈 README 파일을 추가합니다.
  - `aws/ktor-dynamodb/README.md`
  - `aws/ktor-dynamodb/README.ko.md`
- AWS 그룹 README 파일 업데이트:
  - `aws/README.md`
  - `aws/README.ko.md`
- 루트 README 파일 업데이트:
  - `README.md`
  - `README.ko.md`
- `docs/images/readme-diagrams/` 아래에 두 개의 README 다이어그램을 추가합니다.
  - `aws-ktor-dynamodb-readme-architecture-01.svg/png`
  - `aws-ktor-dynamodb-readme-sequence-01.svg/png`
- 두 모듈 README 로케일 모두 개요에 대한 동등한 섹션을 포함해야 합니다.
  아키텍처, API, 로컬 실행, 테스트 명령, LocalStack 패리티, 실제 AWS
  옵트인, 오류 처리, 지원되지 않는 기능 및 정리.
- README 파일에는 복사하여 붙여 넣을 수 있는 HTTP 생성, 읽기,
  제한된 목록, 업데이트, 삭제, 복제 만들기 `409`, 오래된 업데이트 `409`,
  `404` 및 validation/malformed JSON `400` 항목이 누락되었습니다.
- README 로컬 명령에는 에뮬레이터 전제조건과 페일클로즈가 포함되어야 합니다.
  엔드포인트 및 더미 자격 증명을 사용하는 로컬 앱 실행 명령:

```bash
./gradlew :aws-ktor-dynamodb:test --max-workers=1
./gradlew :aws-ktor-dynamodb:test --max-workers=1 -Dbluetape4k.aws.emulator=localstack

# Start a local AWS-compatible emulator first, then pass its endpoint explicitly.
./gradlew :aws-ktor-dynamodb:run \
  -Dbluetape4k.aws.mode=local \
  -Dbluetape4k.aws.region=ap-northeast-2 \
  -Dbluetape4k.aws.dynamodb.table-name=workshop-order-sessions-local \
  -Dbluetape4k.aws.dynamodb.endpoint-url=http://localhost:4566 \
  -Dbluetape4k.aws.access-key-id=test \
  -Dbluetape4k.aws.secret-access-key=test

curl -fsS http://localhost:8080/health/readiness
```

- 실제 AWS README 명령은 advanced/optional로 명확하게 표시되어야 합니다.
  CI에서 사용되며 명시적 모드, 지역, 테이블 이름 및 AWS이 필요해야 합니다.
  default/environment 자격 증명:

```bash
./gradlew :aws-ktor-dynamodb:run \
  -Dbluetape4k.aws.mode=real \
  -Dbluetape4k.aws.region=ap-northeast-2 \
  -Dbluetape4k.aws.dynamodb.table-name=workshop-order-sessions
```

- 다이어그램은 위에서 아래로 또는 명확하게 표시되는 최신 모범 사례 스타일을 사용해야 합니다.
  아키텍처의 레이어 흐름, 번호가 매겨진 시퀀스 호출 레이블, 투명 대체
  지역, 정렬된 카드 텍스트, 아이콘이 있는 공식 AWS/DynamoDB 시각적 개체
  사용됨, 선 스타일이 다를 때 커넥터 범례, 둥근 직교
  커넥터, 체크리스트 검증 및 전체 크기 육안 검사.
- 다이어그램은 기본 로컬 에뮬레이터 경계(선택 사항)를 명시적으로 표시해야 합니다.
  실제 AWS 경계, 경계 list/pagination 및 `400`/`404`/`409` 실패
  경로.

## CI 및 등록

- `settings.gradle.kts`: `aws/*` 모듈이 있기 때문에 수동 변경이 예상되지 않습니다.
  자동 포함됩니다.
- `gradle/libs.versions.toml`: 새 버전에 필요한 버전 없는 별칭만 추가합니다.
  모듈, 기존 버전 항목과 루트 BOM을 재사용합니다.
- `.github/workflows/Examples.yml`: `aws/ktor-dynamodb/**` 경로 필터를 추가하고
  기존 순차에 `:aws-ktor-dynamodb:test` 추가
  `container-examples` 작업, 아티팩트 경로 포함:
  - `aws/ktor-dynamodb/build/test-results/test/*.xml`
  - `aws/ktor-dynamodb/build/reports/tests/test/`
- `.github/workflows/nightly.yml`: 매주이므로 목표 변경이 예상되지 않습니다.
  전체 실행 `./gradlew test --continue --max-workers=1`; 프로젝트 후 확인
  등록.
- `scripts/smoke-validate.sh`: Docker 지원에 `:aws-ktor-dynamodb:test` 추가
  `all-smoke`이 아닌 그룹에만 해당됩니다. 오래된 확인 예상 프로젝트 수를 95로 업데이트합니다.
- `./gradlew projects --console=plain`은 `:aws-ktor-dynamodb`를 나열해야 합니다.

## 수락 기준

- `:aws-ktor-dynamodb`이 존재하고 컴파일됩니다.
- 모든 프로덕션 종속성은 루트 BOM 또는 버전 카탈로그 별칭을 사용합니다. 아니요
  bluetape4k 버전은 로컬로 고정되어 있습니다.
- 기본 테스트는 실제 AWS을 호출하지 않습니다.
- 테스트에는 생성, 업데이트, 중복 조건부 실패, 오래된 버전이 포함됩니다.
  조건부 실패, 제한된 list/pagination, 동시 오래된 업데이트,
  조회 누락, 삭제, 잘못된 JSON, 준비 및 직렬화.
- README.md 및 README.ko.md가 모듈에 대해 존재하며 로컬 run/test에 대해 설명합니다.
  명령과 보호된 실제 AWS 옵트인, 정리 및 지원되지 않는 기능.
- 루트 및 AWS README 로케일 쌍은 새 모듈을 일관되게 언급합니다.
- 아키텍처 및 시퀀스 다이어그램에는 SVG 및 PNG 출력이 있으며 전체
  `$bluetape4k-diagram` 체크리스트와 전체 크기 육안 검사.
- CI/container 등록이 새로운 Testcontainers 지원에 대해 업데이트되었습니다.
  기준 치수.
- 실제 AWS 모드는 `-Dbluetape4k.aws.mode=real`에 의해 보호되며
  tests/CI, environment/default 자격 증명만 사용합니다.
- 성공적인 업데이트는 하나의 `UpdateItem`을 사용합니다. 업데이트 실패는 최대 1개를 사용합니다.
  실패 `UpdateItem` + 1 `GetItem`; 목록은 경계가 있고 페이지가 매겨져 있습니다.
- `./gradlew :aws-ktor-dynamodb:test --max-workers=1` 통과.
- `./gradlew projects --console=plain`은 모듈을 전달하고 포함합니다.
- `git diff --check` 통과.

## 위험 및 완화

| 위험 | 완화 |
| --- | --- |
| 에뮬레이터 시작 불안정 | 공유 `bluetape4k-testcontainers` 실행 프로그램, 고유한 테이블 이름 및 직렬 테스트를 사용하세요. |
| 누락된 버전과 오래된 버전 간의 조건부 실패 모호성 | 성공적인 업데이트를 미리 읽지 마십시오. 대체 `GetItem`을 사용하여 조건부 업데이트가 실패한 후에만 명확하게 합니다. |
| AWS SDK 모델 API 드리프트 | 현재 카탈로그에 대해 컴파일하고 기존 `bluetape4k-aws` 예제를 소스 증거로 사용합니다. |
| README 다이어그램 품질 회귀 | 전체 다이어그램 체크리스트를 실행하고 PR 이전의 전체 크기 PNG 출력을 검사합니다. |
| CI 런타임 증가 | 모듈을 용기 없는 연기로부터 멀리 두십시오. 순차 컨테이너 지원 레인에만 추가합니다. |

## 2단계-R 7계층 사양 검토 로그

초기 검토 결과:

| 계층 | P0 | P1 | 주요 결과 | 해결 |
| --- | --- | --- | --- | --- |
| 성과 | 0 | 2 | 업데이트 전 읽기로 인해 성공 경로가 두 개의 명령으로 만들어졌습니다. 목록은 무제한 스캔이었습니다. | DynamoDB 명령 예산, 단일 명령 업데이트 성공, 실패 전용 `GetItem` 명확성, 페이지가 제한된 목록이 추가되었습니다. |
| 안정성 | 0 | 2 | 테스트 상태 블리드 및 timeout/deadline 동작이 제대로 지정되지 않았습니다. | 고유한 table/items, 정리, 병렬 실행 비활성화, 제한된 시간 초과, 제한된 재시도 요구 사항이 추가되었습니다. |
| 보안 | 0 | 0 | 인증 경계, 표현식 삽입, 로그 수정 및 JSON 안전에는 더 강력한 요구 사항이 필요했습니다. | 보안 기본값, 표현식 바인딩 규칙, 안전한 로깅, DTO 전용 직렬화, 자격 증명 처리가 추가되었습니다. |
| 운영자 | 0 | 0 | 관찰 가능성, CI 레인, 리소스 소유권 및 실제 AWS Runbook이 너무 느슨했습니다. | 작업 섹션, 정확한 컨테이너 레인, 아티팩트 경로, local/LocalStack/real-AWS 명령, 정리 규칙을 추가했습니다. |
| Developer/API | 0 | 2 | 조건부 update/delete 계약은 원자적이지 않았습니다. 종속성과 JSON 스택이 명확하지 않았습니다. | 조건부 실패 명확성, 조건부 삭제, `aws-kotlin` 버전 참조 별칭 규칙, `kotlinx.serialization` DTO 규칙을 추가했습니다. |
| User/Caller | 0 | 2 | 로컬 실행 경로와 실제 AWS 옵트인이 제대로 지정되지 않았습니다. | 정확한 README 명령 세트, 명시적인 실제 AWS 가드, cost/cleanup 경고, parity/API 예제 요구 사항이 추가되었습니다. |
| 주요 통합 | 0 | 0 | P1 결과가 중복되어 사양에서 모두 해결 가능했습니다. | 위의 수정 사항을 적용했습니다. 2-R단계를 닫기 전에 영향을 받은 차선을 다시 운행하십시오. |

수렴 요구 사항: 2-R단계는 영향을 받은 재실행 레인이 보고된 경우에만 닫힙니다.
P0 = 0 및 P1 = 0.

최종 수렴:

| 계층 | 최종 상태 | 증거 |
| --- | --- | --- |
| 성과 | PASS | 재실행하여 업데이트 전 읽기 모순, 준비 TTL/command 예산 및 구체적인 런타임 임계값이 없음을 확인했습니다. |
| 안정성 | PASS | 확인된 재시도 범위, 시간 초과, 상태 격리 및 수명 주기 정리 요구 사항을 다시 실행합니다. |
| 보안 | PASS | 재실행 확인됨 P0/P1/P2 = 0; 남은 요청 크기 표현은 계획의 구현 세부 사항이 되었습니다. |
| 운영자 | PASS | 재실행 확인됨 P0/P1 = 0; mode/emulator 용어는 검토 후 표준화되었습니다. |
| Developer/API | PASS | 확인된 준비 계약, 빌드 계약, DTO 모양 및 Gradle 별칭 현실성을 다시 실행합니다. |
| User/Caller | PASS | 확인된 로컬 명령, 실제 AWS 가드레일, README 패리티 및 학습자 예제를 다시 실행합니다. |
| 주요 통합 | PASS | 로컬 `git diff --check -- docs/superpowers/specs/2026-07-01-issue-325-ktor-dynamodb-local-first-design.md`이(가) 통과되었습니다. |

2단계-R 게이트: P0 = 0이고 P1 = 0인 PASS.
