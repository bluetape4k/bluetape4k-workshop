# Issue #325 - Ktor DynamoDB 지역 우선 추진 계획

> **For agentic workers:** REQUIRED SUB-SKILLS: Use `bluetape4k-workflow`,
> `bluetape4k-code-patterns`, `bluetape4k-blog`, `bluetape4k-diagram`,
> `test-driven-development` 및 `verification-before-completion`. 단계 사용
> 추적을 위한 확인란(`- [ ]`) 구문입니다.

**목표:** 다음을 시연하는 로컬 우선 Ktor + DynamoDB 워크숍 모듈을 추가합니다.
`DynamoDbKtorPlugin`, 조건부 생성, 낙관적 업데이트, 제한된 페이지 매김,
안전한 오류 매핑 및 에뮬레이터 지원 검증.

**사양:** `docs/superpowers/specs/2026-07-01-issue-325-ktor-dynamodb-local-first-design.md`

**아키텍처:** Ktor 경로는 HTTP을 소유하고 유효성 검사를 요청합니다. 안
`OrderSessionService`은(는) 도메인 의미와 오류 매핑을 소유합니다. 얇은
`OrderSessionDynamoRepository`은 create/read에 `DynamoDbKtorRepository`를 사용합니다.
제한된 스캔을 위한 경로 및 AWS Kotlin SDK DynamoDB 명령, 조건부
생성, 낙관적 업데이트 및 조건부 삭제. 테스트 공급
Floci/LocalStack 엔드포인트, 지역, 자격 증명, 고유 테이블 이름 및 명령
`bluetape4k-testcontainers`을 통한 계측 계측.

**기술 스택:** Kotlin, Ktor server/test 호스트, kotlinx 직렬화,
AWS SDK for Kotlin DynamoDB, `bluetape4k-aws-ktor`, `bluetape4k-aws-kotlin`,
`bluetape4k-testcontainers`, JUnit 5, `bluetape4k-assertions`,
`SuspendedJobTester`, CairoSVG 렌더링된 README 다이어그램.

---

## 파일 구조

- `aws/ktor-dynamodb/build.gradle.kts` 생성
- `aws/ktor-dynamodb/src/main/kotlin/io/bluetape4k/workshop/aws/ktordynamodb/*` 생성
- `aws/ktor-dynamodb/src/main/resources/logback.xml` 생성
- `aws/ktor-dynamodb/src/test/kotlin/io/bluetape4k/workshop/aws/ktordynamodb/*` 생성
- `aws/ktor-dynamodb/src/test/resources/junit-platform.properties` 생성
- `aws/ktor-dynamodb/src/test/resources/logback-test.xml` 생성
- `aws/ktor-dynamodb/README.md` 생성
- `aws/ktor-dynamodb/README.ko.md` 생성
- `gradle/libs.versions.toml` 수정
- `README.md` 수정
- `README.ko.md` 수정
- `aws/README.md` 수정
- `aws/README.ko.md` 수정
- `.github/workflows/Examples.yml` 수정
- `scripts/smoke-validate.sh` 수정
- 새 다이어그램 파일 이름을 등록해야 하는 경우 다이어그램 유효성 검사기를 수정합니다.
  `scripts/validate-readme-architecture-diagrams.mjs`,
  `scripts/validate-sequence-diagrams.mjs`
- `docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.svg/png` 생성
- `docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.svg/png` 생성
- `docs/review/2026-07-01-issue-325-implementation-review.md` 생성
- `docs/lessons/2026-07-01-issue-325-ktor-dynamodb-local-first.md` 생성

## 종속성 및 API 가드

- [ ] 카탈로그 별칭을 추가합니다.
  - `bluetape4k-aws-ktor = { module = "io.github.bluetape4k.aws:bluetape4k-aws-ktor" }`
  - `bluetape4k-aws-kotlin = { module = "io.github.bluetape4k.aws:bluetape4k-aws-kotlin" }`
  - `aws-kotlin-dynamodb = { module = "aws.sdk.kotlin:dynamodb", version.ref = "aws-kotlin" }`
  - `aws2-auth = { module = "software.amazon.awssdk:auth", version.ref = "aws2" }` 컴파일 증거가 Java SDK v2 인증 도우미가 필요하다는 것을 증명하는 경우에만 해당됩니다.
- [ ] 루트 BOM 아래에 bluetape4k 별칭을 버전 없이 유지합니다.
- [ ] 모듈 빌드에 `alias(libs.plugins.kotlin.serialization)`을 적용합니다.
- [ ] `implementation(platform(libs.ktor.bom))`, `libs.ktor.serialization.kotlinx.json`, `libs.kotlinx.serialization.json`를 사용하세요.
- [ ] 구현하기 전에 현재 로컬 소스를 확인합니다.
  `DynamoDbKtorPlugin`, `Application.dynamoDb()`,
  `DynamoDbKtorRuntime.repository(...)`, `DynamoDbKtorRepository.put/findById`,
  `DynamoItemMapper`, `DynamoItemReader`, `partitionKeyOf`,
  `stringAttrDefinitionOf`, `FlociServer.Launcher.floci` 및
  `LocalStackServer.Launcher.getLocalStack("dynamodb")`.
- [ ] 사용하기 전에 `DynamoDbKtorRepository.scan`의 로컬 소스 API를 확인합니다.
      페이지네이터 `Flow`인 경우 학습자용 경계 페이지에 사용하지 마세요.
      목록 API; 한 페이지에 AWS Kotlin `DynamoDbClient.scan`을 직접 사용하세요.

## 오류 코드 매트릭스

모든 경로 테스트, README 컬 예제 및 StatusPages 매핑은
HTTP 상태와 안정적인 `ErrorResponse.code`, 상태만 있는 것이 아닙니다.

| 상태 | 상태 | 코드 | 안전 메시지 규칙 |
| --- | --- | --- | --- |
| 비어 있거나 유효하지 않은 필드 | `400` | `VALIDATION_FAILED` | 페이로드가 아닌 잘못된 필드의 이름을 지정하세요. |
| 잘못된 JSON | `400` | `MALFORMED_JSON` | 파서 텍스트나 요청 본문을 에코하지 마세요. |
| 특대 몸체 | `413` | `REQUEST_TOO_LARGE` | 페이로드 콘텐츠를 구문 분석하거나 기록하지 마세요. |
| malformed/foreign `nextToken` | `400` | `INVALID_PAGE_TOKEN` | 디코딩된 토큰 내부를 공개하지 마세요. |
| 중복 생성 | `409` | `ORDER_SESSION_EXISTS` | ID로만 충돌을 언급하세요. |
| 오래된 낙관적 업데이트 | `409` | `ORDER_SESSION_VERSION_CONFLICT` | expected/current 불일치를 안전하게 언급하세요. |
| read/update/delete 누락 | `404` | `ORDER_SESSION_NOT_FOUND` | ID로만 멘션을 찾을 수 없습니다. |
| 준비 상태 새로 고침 down/inactive | `503` | `DYNAMODB_NOT_READY` | 엔드포인트, AWS 요청 ID 또는 원시 AWS 메시지가 없습니다. |
| 예상치 못한 DynamoDB/emulator 실패 | `503` | `DYNAMODB_UNAVAILABLE` | 안정적인 메시지; 세부 정보는 삭제된 로그 필드로만 제공됩니다. |

## 작업 1: 모듈 뼈대

**복잡성:** 중간

**파일:**
- `aws/ktor-dynamodb/build.gradle.kts` 생성
- `aws/ktor-dynamodb/src/main/resources/logback.xml` 생성
- `aws/ktor-dynamodb/src/test/resources/junit-platform.properties` 생성
- `aws/ktor-dynamodb/src/test/resources/logback-test.xml` 생성
- `gradle/libs.versions.toml` 수정

- [ ] `application`을 사용하여 Gradle 빌드를 생성합니다.
      `alias(libs.plugins.kotlin.serialization)`, Ktor 서버 Netty,
      `ContentNegotiation`, `CallLogging`, `StatusPages`, 코틀린스 JSON,
      AWS Kotlin DynamoDB, `bluetape4k-aws-ktor`,
      `bluetape4k-aws-kotlin`, `bluetape4k-ktor-core`,
      `bluetape4k-logging`, `bluetape4k-coroutines`, JUnit 5,
      Ktor 테스트 host/client, `bluetape4k-testcontainers` 및
      `bluetape4k-assertions`.
- [ ] `mainClass`를 구성합니다.
      `io.bluetape4k.workshop.aws.ktordynamodb.KtorDynamoDbApplicationKt`.
- [ ] `junit-platform.properties`에서 JUnit 병렬 실행을 비활성화합니다.
- [ ] 인접 모듈과 일치하는 테스트 로그백 구성을 추가합니다.
- [ ] 컨텍스트 모드를 통해 `./gradlew projects --console=plain`을 실행하고 확인합니다.
      `:aws-ktor-dynamodb`.
- [ ] 달리다
      `./gradlew :aws-ktor-dynamodb:compileKotlin --warning-mode all --console=plain`.

## 작업 1.5: 행동적 Red 테스트를 위한 최소 컴파일 스켈레톤

**복잡성:** 중간

**파일:**
- 자리 표시자 동작을 사용하여 작업 3에 나열된 최소 프로덕션 파일을 만듭니다.

- [ ] 진입점, 경로 설치 프로그램, 구성 솔기, service/repository 추가
      인터페이스, DTO/data 클래스 이름 및 오류 유형을 포함하므로 테스트는 이전에 컴파일됩니다.
      완전한 동작이 존재합니다.
- [ ] 기본적으로 비사용자 API classes/functions `internal`를 표시합니다. 영어 추가
      계약이 포함된 KDoc 및 공개 class/function에 대한 간단한 예
      공개 상태로 유지됩니다.
- [ ] 도메인 모델, 구성 값,
      페이지 토큰 값 및 DTO는 `java.io.Serializable`을 구현하고 정의합니다.
      동료 `serialVersionUID`.
- [ ] DTO/config 구성에는 명명된 인수를 사용합니다. 내부 기능의 경우
      두 개 이상의 동일한 유형의 매개변수가 필요한 경우 명명된 값을 도입하세요.
      object/factory 위치 `String`/`Long` 쌍에 의존하는 대신.
- [ ] 예를 들어 자리표시자 구현을 명시적이고 결정적으로 유지하세요.
      `TODO("implemented in Task 3")`; 빨간색 테스트는 컴파일되고 실패해야 합니다.
      동작적으로는 해결되지 않은 참조로 인해 실패하지 않습니다.
- [ ] 달리다
      `./gradlew :aws-ktor-dynamodb:compileTestKotlin --warning-mode all --console=plain`
      빨간색 테스트를 추가한 후 첫 번째 전체 `test` 실행 전.

## 작업 2: TDD 빨간색 테스트

**복잡성:** 높음

**파일:**
- `aws/ktor-dynamodb/src/test/kotlin/io/bluetape4k/workshop/aws/ktordynamodb/KtorDynamoDbApplicationTest.kt` 생성
- 필요한 경우 동일한 패키지 아래에 테스트 지원을 만듭니다.

- [ ] 먼저 `testApplication` 및 Ktor JSON 클라이언트를 사용하여 테스트를 추가합니다.
      `ContentNegotiation { json(...) }`.
- [ ] 러너 규칙: 경로 테스트는 `testApplication {}`을 직접 사용합니다. IO-바운드
      직접 일시 중지 테스트 및 setup/cleanup `runSuspendIO` 사용; 사용하지 마십시오
      Ktor `testApplication`, Testcontainers 또는 실제 IO의 경우 `runTest`입니다.
- [ ] 기본적으로 `FlociServer.Launcher.floci`을 사용하고
      `LocalStackServer.Launcher.getLocalStack("dynamodb")` 언제
      `-Dbluetape4k.aws.emulator=localstack`.
- [ ] `Base58.randomString(8)` 또는 다른 방법을 통해 고유한 테이블 이름을 사용하십시오.
      bluetape4k 승인 고유 문자열 도우미; 테스트마다 고유한 항목 ID를 사용하세요.
- [ ] 클래스별 테이블 이름, 테스트 소유 ID 접두사, 범위가 지정된 목록 어설션 사용
      소유한 ID에 `@AfterAll` 최선의 노력 `DeleteTable`/item 정리
      제한된 시간 초과. 정리 실패는 정리된 테스트로 기록됩니다.
      성공 증거로 숨겨지지 않은 진단.
- [ ] 작업 이름별로 DynamoDB SDK 작업을 계산할 수 있는 테스트 지원을 추가합니다.
      생성, 복제 생성, 읽기, 나열을 위한 명령 예산을 검증문합니다.
      업데이트 성공, 업데이트 실패 폴백, 삭제, 유효성 검사 실패 및
      준비 프로브.
- [ ] 다음에 대한 빨간색 테스트를 추가합니다.
  - 하나의 주문 세션을 생성한 후 읽습니다.
  - 제한된 목록 기본 제한, 최대 제한, 연속 토큰, 잘못된 형식
    토큰 `400 INVALID_PAGE_TOKEN` 및 페이지당 하나의 `Scan` 호출,
  - 중복 생성은 하나가 실패한 `409 ORDER_SESSION_EXISTS`를 반환합니다.
    `PutItem`,
  - 일치하는 `expectedVersion` 증분 `version`으로 업데이트합니다.
  - 업데이트 성공 시 `UpdateItem` 하나만 사용하고 `GetItem`은 사용하지 않습니다.
  - 오래된 업데이트는 최대 하나의 `409 ORDER_SESSION_VERSION_CONFLICT`을 반환합니다.
    실패 `UpdateItem` + 하나의 대체 `GetItem`,
  - 누락된 read/update/delete은 `404 ORDER_SESSION_NOT_FOUND`을 반환합니다.
  - 조건부 삭제 성공은 `204`을 반환합니다.
  - 잘못된 상태, 공백 id/customer id/table 이름, 양수가 아님
    `expectedVersion` 및 `limit` 외부 `1..100`는 안전한 `400` 코드를 반환합니다.
  - 잘못된 JSON은(는) `400 MALFORMED_JSON`를 반환합니다.
  - `64 KiB`보다 큰 JSON가 0인 `413 REQUEST_TOO_LARGE`를 반환합니다.
    DynamoDB 명령,
  - 준비 상태는 `200 ReadinessResponse(status="UP")`을 반환합니다.
  - TTL이 DynamoDB 명령을 실행하기 전에 반복적인 준비 상태 조사,
  - 준비 TTL 새로 고침은 최대 하나의 `DescribeTable` 호출,
  - 2초 이내의 준비 새로 고침 시간 초과는 안전한 `503 DOWN`을 반환합니다.
  - 시작 테이블 부트스트랩 timeout/failure이(가) 애플리케이션 시작에 실패할 수 있습니다.
  - 예상치 못한 DynamoDB/emulator 실패는 안전한 `503 DYNAMODB_UNAVAILABLE`에 매핑됩니다.
  - repository/service `CancellationException`은 매핑되지 않고 다시 발생합니다.
    재시도했지만 `runCatching`에 의해 무시되지 않았고 DynamoDB 오류로 기록되지 않았습니다.
  - `#id`, `:expected`,
    `attribute_exists(id)`, 따옴표 및 구분 기호는 stored/compared입니다.
    경계값만,
  - JSON 직렬화는 DTO를 왕복하며 kotlinx 직렬 변환기만 사용합니다.
- [ ] `SuspendedJobTester`을 사용하여 제한된 경합 테스트를 추가합니다. 동시
      동일한 예상 버전의 업데이트는 최소 8개의 동시 작업을 사용합니다.
      10초의 경로 테스트 시간 초과, 정확히 한 번의 성공, `N - 1` `409`
      수면 기반 루프 없이 응답합니다.
- [ ] AWS SDK 로컬 테스트 재시도를 증명하는 구성 수준 어설션 또는 테스트 이음매를 추가합니다.
      경계: 최대 시도 횟수 2, 총 호출 기한 5초, 백오프 한도 500
      밀리초, 유효성 검사 실패에 대한 도메인 수준 재시도 없음 또는
      `ConditionalCheckFailedException`.
- [ ] 원시 URI 쿼리, 헤더가 없음을 입증하는 캡처된 로그 수정 테스트를 추가합니다.
      본문, 엔드포인트, 자격 증명, AWS 요청 ID, 원시 AWS 메시지 또는
      페이로드는 로그 또는 오류 응답에 표시됩니다.
- [ ] `bluetape4k-assertions`만 사용하세요. AssertJ, Kluent, JUnit을 소개하지 마세요.
      검증문 또는 `kotlin.test` 검증문.
- [ ] 달리다
      `./gradlew :aws-ktor-dynamodb:compileTestKotlin --warning-mode all --console=plain`
      첫 번째 완전 빨간색 테스트 실행 전에 테스트 컴파일을 확인합니다.
- [ ] 달리다
      `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1`
      생산 구현 전에 예상되는 빨간색 오류를 기록합니다.

## 작업 3: 애플리케이션, DTO 및 저장소

**복잡성:** 높음

**파일:**
- `KtorDynamoDbApplication.kt` 생성
- `DynamoDbLocalConfig.kt` 생성
- `OrderSessionModels.kt` 생성
- `OrderSessionRepository.kt` 생성
- `OrderSessionService.kt` 생성
- `OrderSessionRoutes.kt` 생성
- `OrderSessionErrors.kt` 생성

- [ ] `@Serializable` DTO 및 기타 모든 새로운 `data class` 값을 구현하여
      그들은 또한 `java.io.Serializable`을 구현하고 정의합니다.
      `serialVersionUID`: `CreateOrderSessionRequest`,
      `UpdateOrderSessionRequest`, `OrderSessionResponse`,
      `OrderSessionListResponse`, `ErrorResponse`, `ReadinessResponse`,
      `OrderSession`, 런타임 구성 및 페이지 토큰 값 클래스.
- [ ] `OrderSessionStatus`을 허용 목록에 포함된 열거형으로 모델화
      `CREATED`, `APPROVED`, `CANCELLED`.
- [ ] `requireNotBlank`과 같은 bluetape4k 검증 도우미를 사용하고
      `requireInRange` 발신자 입력용; 내부용으로만 `check`을 사용하세요.
      불변.
- [ ] 작업 수준에서 필드 유효성 검사: `id`, `customerId` 및 `tableName`
      비어 있지 않음; `expectedVersion > 0`; `limit in 1..100`; 기형이거나 이물질
      `nextToken`은 `400 INVALID_PAGE_TOKEN`을 반환합니다. local/test 엔드포인트 및
      모드가 local/test인 경우 더미 자격 증명 속성이 존재합니다.
- [ ] kotlinx JSON, `CallLogging`로 Ktor `ContentNegotiation`을 구성하고
      `StatusPages`.
- [ ] Ktor kotlinx DTO 시리얼라이저만 사용하세요. Jackson 콘텐츠를 설치하지 마세요
      협상, Jackson 기본 타이핑 또는 이에 대한 다형성 직렬화
      기준 치수. dependency/config grep 및 직렬화 테스트를 통해 확인합니다.
- [ ] 구체적인 `64 KiB` 최대값으로 요청 본문 크기를 구성하고 안전을 반환합니다.
      `413 REQUEST_TOO_LARGE` parsing/logging 대형 페이로드 전.
- [ ] 메서드, 경로 템플릿, 상태만 기록하도록 `CallLogging`을 구성합니다.
      진단 ID, 작업 및 안전 오류 클래스입니다. 원시 URI 쿼리를 기록하지 마세요.
      헤더, 본문, 엔드포인트, 자격 증명, AWS 요청 ids/messages 또는
      페이로드.
- [ ] 유효성 검사 실패, 잘못된 수신/수신에 대해 `StatusPages`을 구성합니다.
      직렬화 예외, 도메인 conflicts/misses, readiness/downstream
      오류 코드 매트릭스를 사용하여 실패 및 예상치 못한 오류를 방지합니다.
- [ ] `autoCreateTables = true`를 사용하여 `DynamoDbKtorPlugin`를 설치합니다.
      `BillingMode.PayPerRequest`, 파티션 키 `id`, 엔드포인트, 지역,
      자격 증명 공급자 및 제한된 테이블 준비 시간 초과.
- [ ] DynamoDB 클라이언트 수명 주기의 이름을 명시적으로 지정합니다.
      local/test 모드는 플러그인 소유의 AWS Kotlin `DynamoDbClient` 빌드를 사용합니다.
      제공된 에뮬레이터 엔드포인트, 지역 및 더미 자격 증명에서
      리얼 모드는 AWS Kotlin default/environment 자격 증명을 사용한 후에만 사용합니다.
      명시적 `-Dbluetape4k.aws.mode=real`; 플러그인 소유 클라이언트는 Ktor에 종료됩니다.
      일시 휴업; 주입된 테스트 클라이언트는 고정 장치의 소유로 유지됩니다.
- [ ] production/main 애플리케이션 코드를 Testcontainers에서 제외하세요.
- [ ] 런타임 구성 구문 분석:
  - 기본값 `-Dbluetape4k.aws.mode=local`,
  - 테스트의 경우 선택 사항 `-Dbluetape4k.aws.emulator=localstack`,
  - 수동 실제 AWS에 대해서만 명시적 `-Dbluetape4k.aws.mode=real`,
  - `-Dbluetape4k.aws.region`,
  - `-Dbluetape4k.aws.dynamodb.table-name`,
  - `-Dbluetape4k.aws.dynamodb.endpoint-url`,
  - `-Dbluetape4k.aws.access-key-id`,
  - `-Dbluetape4k.aws.secret-access-key`.
- [ ] 에뮬레이터 엔드포인트 또는 더미가 local/test 모드에서 실패하면 종료됨
      자격 증명이 없습니다. default/local 모드가 절대 없음을 증명하는 테스트를 추가합니다.
      실제 AWS SDK 기본값으로 폴백하고 실제 모드에서는 명시적인
      `mode=real` 플래그입니다.
- [ ] local/test 모드의 경우 테스트는 더미 자격 증명과 에뮬레이터 엔드포인트를 제공합니다.
      리얼 모드의 경우 AWS Kotlin default/environment 자격 증명 공급자를 사용하세요.
      하드코드된 키가 없는 체인.
- [ ] `OrderSessionDynamoRepository` 구현:
  - `PutItem` 및 `attribute_not_exists(#id)`을 사용하여 생성합니다.
  - `GetItem`으로 읽고,
  - AWS Kotlin `DynamoDbClient.scan`을 직접 사용하는 경계 목록, 하나의 `Scan`
    페이지, 기본 제한 25, 최대 100, `exclusiveStartKey`,
    `lastEvaluatedKey`, 불투명 토큰 encode/decode, 잘못된 토큰 `400`,
  - 다음을 사용하여 `UpdateItem` 하나로 업데이트 성공
    `attribute_exists(#id) AND #version = :expected`,
  - 한 번의 대체 `GetItem`로 인한 업데이트 실패 이후에만
    `ConditionalCheckFailedException`,
  - 조건부 `DeleteItem attribute_exists(#id)`으로 삭제합니다.
- [ ] 요청 제어 문자열을 DynamoDB 표현식에 연결하지 마십시오.
      고정된 `ExpressionAttributeNames`과 바인딩된 `AttributeValue`을 사용하세요.
- [ ] 광범위한 예외 매핑 전에 `CancellationException`을 다시 발생시킵니다.
- [ ] 정리된 구조화된 필드(작업, 안전 오류 클래스 및)만 기록합니다.
      진단 ID. 원시 AWS 메시지, 요청 ID, 엔드포인트,
      헤더, 자격 증명 또는 페이로드.
- [ ] 준비 상태 구현:
  - startup/table 부트스트랩 실패로 인해 애플리케이션 시작이 실패할 수 있습니다.
  - 일반 프로브는 캐시된 상태를 사용하고 DynamoDB 명령을 실행하지 않습니다.
  - 선택적 TTL 30초마다 최대 하나의 `DescribeTable` 새로 고침
    2초 타임아웃,
  - `UP`의 경우 `200`, 시작 후 새로 고침의 경우 `503` down/inactive.
- [ ] 테스트를 위해 주입 가능한 clock/checker 솔기를 추가합니다. 아니오를 확인하세요
      준비 상태 새로 고침 job/client은 `testApplication` 중지 후에도 유지됩니다.
- [ ] AWS SDK 로컬 테스트 재시도 범위 사용: 최대 시도 횟수 2, 총 호출 기한
      5초, 백오프 한도는 500밀리초입니다.
- [ ] 녹색이 될 때까지 집중 테스트를 순차적으로 실행합니다.

## 작업 4: README 및 다이어그램 자산

**복잡성:** 높음

**파일:**
- `aws/ktor-dynamodb/README.md` 생성
- `aws/ktor-dynamodb/README.ko.md` 생성
- `README.md` 수정
- `README.ko.md` 수정
- `aws/README.md` 수정
- `aws/README.ko.md` 수정
- `docs/images/readme-diagrams/` 아래에 SVG/PNG 다이어그램 만들기

- [ ] 다이어그램을 만들기 전에 `$bluetape4k-diagram`을 새로 로드하세요.
- [ ] 언어 전환을 통해 영어 및 한국어 모듈 README를 작성하고
      소스에 해당하는 섹션: 개요, 아키텍처, API, 로컬 실행,
      테스트 명령, LocalStack 패리티, 실제 AWS 선택, 오류 처리,
      지원되지 않는 기능, 정리 및 다이어그램.
- [ ] 로컬 실행을 실행 가능한 Runbook으로 문서화합니다. 메인 코드는
      Testcontainers 시작, README에는 에뮬레이터 전제조건이 포함되어야 합니다.
      엔드포인트 URL, 지역, 테이블 이름, 더미 자격 증명, 앱 시작 명령,
      준비 컬 및 stop/cleanup 단계. 발표하지 않음
      `./gradlew :aws-ktor-dynamodb:run -Dbluetape4k.aws.mode=local` 없이
      엔드포인트 및 자격 증명.
- [ ] 생성, 읽기, 제한된 목록에 대한 복사-붙여넣기 가능한 `curl` 예제를 포함합니다.
      업데이트, 삭제, 복제 만들기 `409`, 오래된 업데이트 `409`, 누락된 항목
      `404`, invalid/malformed JSON `400` 및 준비 상태.
- [ ] 컬 예제에서는 `BASE_URL`을 사용하세요. 성공 사례를 순서대로 유지
      create/read/list/update/delete 흐름. `curl -i`을 사용하거나
      부정적인 사례의 경우 `-w '%{http_code}'`을 통해 학습자가 상태 및
      몸. 제한된 목록 `nextToken` 재사용 및 부정 응답 본문 표시
      정확한 오류 코드 매트릭스 코드를 사용합니다.
- [ ] 실제 AWS 모드를 advanced/optional으로 표시하고
      `-Dbluetape4k.aws.mode=real`, tests/CI에서 제외되고 비용이 필요함
      그리고 청소 인식.
- [ ] 인증되지 않은 워크샵 전용 경고 포함: 경로를 변경하면 안 됩니다.
      인증, 승인, 네트워크 없이 공개적으로 노출됩니다.
      제어, 최소 권한 IAM, 속도 제한, 본문 제한 등이 있습니다.
- [ ] 지원되지 않는 범위를 명시적으로 포함: IAM 정책 관리, 공개
      노출, 프로덕션 마이그레이션, GSI, 스트림, 트랜잭션, 프로덕션
      쿼리 디자인 및 스키마 진화.
- [ ] 실제 AWS 테이블 삭제를 위한 복사-붙여넣기 정리 명령을 포함하고
      local/emulator 해당되는 경우 테이블 정리. CI 및 기본값을 명시하세요.
      테스트에서는 실제 AWS 모드를 사용하지 않습니다.
- [ ] 루트 및 AWS README 로케일 쌍을 모듈 행으로 업데이트하고 초점을 맞췄습니다.
      Gradle 명령.
- [ ] 아키텍처 다이어그램 만들기:
  - 위에서 아래로 또는 명확하게 계층화된 흐름,
  - 보이는 로컬 에뮬레이터 경계 및 선택적인 실제 AWS 경계,
  - Ktor route/service/repository/plugin/runtime/DynamoDB 테이블 레이어,
  - 선 스타일이 다른 경우 커넥터 범례
  - 공식 AWS/DynamoDB 아이콘은 사용 시 공유 카탈로그에서만 표시됩니다.
  - 정렬된 카드 텍스트와 둥근 직교 커넥터.
- [ ] 시퀀스 다이어그램 만들기:
  - 회선 위에 번호가 매겨진 통화 라벨,
  - 투명한 alt/branch 영역,
  - 지점별 음소거 색상,
  - 색상이 일치하는 화살촉,
  - 통화 회선을 덮는 라벨이 없습니다.
  - `400`/`404`/`409` 경로 및 경계가 있는 list/pagination.
- [ ] SVG 렌더링:
      `~/.local/bin/cairosvg <svg> -o <png> -s 2`.
- [ ] 새 SVG에서 `xmllint --noout`을 실행합니다.
- [ ] 전체 `$bluetape4k-diagram` 체크리스트 및 저장소 유효성 검사기를 실행합니다.
  - `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.svg docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.svg`
  - `node scripts/validate-readme-architecture-diagrams.mjs`
  - `node scripts/validate-sequence-diagrams.mjs`
  - 해당 geometry/endpoint/mixed-corner/connector/marker/label 감사.
- [ ] 검토 아티팩트에 다이어그램 증거 기록: 원장 행, 커넥터
      및 마커 수, 지오메트리 감사 출력, `WEAK`/`UNAVAILABLE` 처리,
      및 전체 크기 PNG 검사 메모.
- [ ] 터치된 모든 PNG을 전체 크기로 열고 커넥터, 텍스트, 카드 등을 거부합니다.
      아이콘, 둥근 모서리, 화살촉, 팔레트 또는 시퀀스 스타일 결함.
- [ ] README 로캘 유효성 검사기를 실행하고 module/root/aws 로캘 쌍을 기록합니다.
      증거:
  - `node scripts/validate-readme-parity.mjs`
  - `node scripts/validate-readme-language.mjs`

## 작업 5: CI, 연기 및 등록

**복잡성:** 중간

**파일:**
- `.github/workflows/Examples.yml` 수정
- `scripts/smoke-validate.sh` 수정
- 필요한 경우 다이어그램 유효성 검사기 허용 목록을 수정합니다.

- [ ] `Examples.yml` 푸시 및 PR 경로 필터에 `aws/ktor-dynamodb/**`을 추가합니다.
- [ ] 기존 순차에 `:aws-ktor-dynamodb:test` 추가
      `container-examples` 직업에만 해당; 비컨테이너 연기 차선에 접근하지 마십시오.
- [ ] 아티팩트 경로를 추가합니다.
  - `aws/ktor-dynamodb/build/test-results/test/*.xml`
  - `aws/ktor-dynamodb/build/reports/tests/test/`
- [ ] 명시적인 `aws-full` Docker 지원 그룹을 생성합니다.
      `scripts/smoke-validate.sh`와 함께
      `:aws-ktor-dynamodb:test --continue --max-workers=1`; 도움말 텍스트를 업데이트하세요.
      `:aws-ktor-dynamodb:test`을 `all-smoke`에서 제외하세요.
- [ ] 이후 부실 확인 예상 프로젝트 수를 94에서 95로 늘립니다.
      `./gradlew projects`은 모듈을 확인합니다.
- [ ] CI/scripts이 실제 AWS을 활성화하지 않거나 AWS 비밀을 참조하는지 확인합니다.
      `rg 'AWS_|mode=real|secrets\\.' .github scripts`.
- [ ] CI에서는 다음과 같은 경우 에뮬레이터 테스트에만 더미 로컬 AWS 환경을 사용하세요.
      필요합니다. `${{ secrets.AWS_* }}` 또는 `bluetape4k.aws.mode=real`을 추가하지 마세요.
- [ ] 기존 모듈 테스트 런타임과 콜드 모듈 테스트 런타임 비교
      `container-examples` 35분 작업 예산 및 작업 시간 초과만 조정
      증거가 필요한 경우.
- [ ] `actionlint .github/workflows/Examples.yml`를 실행하세요.
- [ ] `./scripts/smoke-validate.sh stale-check`를 실행하세요.
- [ ] 가능하다면 `./scripts/smoke-validate.sh aws-full`을 순차적으로 실행하세요.

## 작업 6: 확인, 검토, 학습 및 PR

**복잡성:** 높음

**파일:**
- `docs/review/2026-07-01-issue-325-implementation-review.md` 생성
- `docs/lessons/2026-07-01-issue-325-ktor-dynamodb-local-first.md` 생성

- [ ] 가능한 경우 IDE 진단을 실행하십시오. 사용할 수 없는 경우 다음으로 대체 기록
      Gradlecompile/tests.
- [ ] 타겟 검증 실행:
  - `./gradlew :aws-ktor-dynamodb:compileKotlin --warning-mode all --console=plain`
  - `./gradlew :aws-ktor-dynamodb:compileTestKotlin --warning-mode all --console=plain`
  - `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1`
  - `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1 -Dbluetape4k.aws.emulator=localstack`
  - `./gradlew projects --console=plain`
  - `./scripts/smoke-validate.sh stale-check`
  - `./scripts/smoke-validate.sh aws-full`
  - `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.svg docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.svg`
  - `node scripts/validate-readme-architecture-diagrams.mjs`
  - `node scripts/validate-sequence-diagrams.mjs`
  - `node scripts/validate-readme-parity.mjs`
  - `node scripts/validate-readme-language.mjs`
  - `actionlint .github/workflows/Examples.yml`
  - `git diff --check`
- [ ] cold/warm 모듈 테스트 런타임 기록:
  - 추운:
    `./gradlew :aws-ktor-dynamodb:cleanTest :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1 --no-build-cache`
  - 따뜻한 즉시 재실행:
    `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1`
  - 웜 런타임이 3분을 초과하거나 콜드 런타임이 6분을 초과하는 경우 에스컬레이션
    분.
- [ ] 6-R단계 7계층 구현 검토를 실행합니다.
  - 성능: 명령 수, 목록 범위, 런타임 예산,
  - 안정성: Testcontainers 격리, timeout/retry 경계, 취소,
  - 보안: credential/log 수정, 표현식 삽입, JSON/body 제한,
  - 연산자: 런북, 준비, 정리, CI 레인,
  - developer/API: DTO/API 모양, 빌드 별칭, 코드베이스 규칙,
  - user/caller: README 패리티, 예제, 다이어그램, 지원되지 않는 범위,
  - 주요 통합: 심각도 정규화 및 P0/P1 수렴.
- [ ] 리뷰 아티팩트 저장
      `docs/review/2026-07-01-issue-325-implementation-review.md`.
- [ ] 맥락, 결정, 결과, 검증 증거 등을 포함하여 수업을 녹음합니다.
      미래 에이전트 안내.
- [ ] Lore 프로토콜로 커밋합니다.
- [ ] PR 해결 #325 생성, `debop` 할당, 이슈 마일스톤 미러링 및
      라벨.
- [ ] `gh pr view`으로 라이브 PR 메타데이터를 확인합니다.
- [ ] `gh pr view --json body`으로 실제 PR 본문을 확인합니다. 마지막 `##` 제목
      `## DoD Status`이어야 합니다.

## 최종 검증 체크리스트

- [ ] `./gradlew :aws-ktor-dynamodb:compileKotlin --warning-mode all --console=plain`
- [ ] `./gradlew :aws-ktor-dynamodb:compileTestKotlin --warning-mode all --console=plain`
- [ ] `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1`
- [ ] `./gradlew :aws-ktor-dynamodb:test --warning-mode all --console=plain --max-workers=1 -Dbluetape4k.aws.emulator=localstack`
- [ ] `./gradlew projects --console=plain`
- [ ] `./scripts/smoke-validate.sh stale-check`
- [ ] `./scripts/smoke-validate.sh aws-full`
- [ ] `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.svg docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.svg`
- [ ] `node scripts/validate-readme-architecture-diagrams.mjs`
- [ ] `node scripts/validate-sequence-diagrams.mjs`
- [ ] `node scripts/validate-readme-parity.mjs`
- [ ] `node scripts/validate-readme-language.mjs`
- [ ] `actionlint .github/workflows/Examples.yml`
- [ ] `xmllint --noout docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.svg`
- [ ] `xmllint --noout docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.svg`
- [ ] `$bluetape4k-diagram` 전체 체크리스트 및 전체 크기 PNG 시력 검사
- [ ] `rg 'AWS_|mode=real|secrets\\.' .github scripts`
- [ ] `git diff --check`

## 3단계-R 7계층 계획 검토 로그

초기 검토: P0 = 0 및 P1 > 0인 REJECT.

| 계층 | P0 | P1 계획에 적용된 테마 |
| --- | --- | --- |
| 성과 | 0 | SDK 명령 수 테스트, 준비 예산 테스트, retry/deadline 증거, `64 KiB` 본체 제한, 더 강력한 경합 형태. |
| 안정성 | 0 | 작업 1.5 컴파일 뼈대, 정리 설비, 취소 테스트, 구체적인 준비 실패 테스트, 수명주기 소유권. |
| 보안 | 0 | 장애 시 로컬 모드, body/log 수정 테스트, 예상치 못한 오류 매핑, 인증 경계 문서, CI 비밀 가드. |
| 운영자 | 0 | 실행 가능한 로컬 Runbook, LocalStack 확인 명령, `aws-full` 스모크 그룹, CI 런타임 체크포인트, 정리 명령. |
| Developer/API | 0 | 한 페이지에 대한 직접 `DynamoDbClient.scan`, 명시적 유효성 검사, internal/KDoc 규칙, 모든 데이터 클래스 직렬화 가능, 실행기 규칙. |
| User/Caller | 0 | README 패리티 유효성 검사기, 오류 코드 매트릭스, 대상 다이어그램 QA command/evidence, 구체적인 컬 및 지원되지 않는 범위 문서. |

최종 재방송:

| 계층 | 최종 상태 | 증거 |
| --- | --- | --- |
| 성과 | PASS | 확인된 명령 수 테스트, 준비 예산 테스트, retry/deadline 증거, 본문 제한, 경합 형태 및 런타임 증거가 명시적입니다. |
| 안정성 | PASS | 확인된 작업 1.5 행동 빨간색 테스트 뼈대, 정리 설비, 취소 테스트, 준비 실패 테스트, timeout/retry 증명 및 수명 주기 소유권. |
| 보안 | PASS | 장애 시 로컬 모드, 요청 크기 제한, log/response 수정, 인증 경계 문서, CI 실제 AWS 가드, 표현식 바인딩 및 kotlinx 전용 JSON을 확인했습니다. |
| 운영자 | PASS | 실행 가능한 로컬 Runbook, LocalStack 테스트 명령, 준비 상태 증거, 정확한 `aws-full` 스모크 그룹, CI 런타임 체크포인트 및 cleanup/cost 명령을 확인했습니다. |
| Developer/API | PASS | 직접 `DynamoDbClient.scan`, 명시적 유효성 검사, internal/KDoc 규칙, 직렬화 가능한 데이터 클래스, 취소 증거, 실행기 규칙 및 컴파일 순서를 확인했습니다. |
| User/Caller | PASS | 확인된 README parity/language 유효성 검사기, 오류 코드 매트릭스, 대상 다이어그램 QA/evidence, 컬 예제, 지원되지 않는 범위 및 전체 크기 눈 검사 게이트. |
| 주요 통합 | PASS | `git diff --check -- docs/superpowers/specs/2026-07-01-issue-325-ktor-dynamodb-local-first-design.md docs/superpowers/plans/2026-07-01-issue-325-ktor-dynamodb-local-first-plan.md`은 spec/plan 정렬 이후에 통과되었습니다. |

3단계-R 게이트: P0 = 0 및 P1 = 0인 PASS.
