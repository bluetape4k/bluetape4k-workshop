# Issue #524 계획 계약 이행 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** Bluetape 가상 스레드, Exposed 리포지토리, PostgreSQL inbox/outbox 일관성, 결정론적 공급자 고정 장치 및 수정된 계획 읽기 모델을 사용하는 Java 25 Spring Boot 계획-계약 워크숍 모듈을 추가합니다.

**아키텍처:** 공급자 중립 `PlanningEngine`은 결정적 기본 어댑터와 두 개의 비활성화된 HTTP 어댑터로 구현됩니다. Spring 서비스는 Bluetape Exposed 저장소 주변의 트랜잭션 경계를 소유합니다. PostgreSQL 발신함은 제출을 유도하고, 고유한 콜백 받은 편지함은 전달을 멱등적으로 만들고, 최종 명령은 후보를 반환하기 전에 집계 버전을 다시 검증합니다.

**기술 스택:** Kotlin 2.3 언어 수준, Java `optimization/*`용 툴체인 25개, Spring Boot 4.1 MVC, HikariCP, JetBrains Exposed JDBC은 `bluetape4k-dependencies:1.3.1`, `bluetape4k-exposed-jdbc`를 통해 해결됨, `bluetape4k-exposed-jdbc-tests`, `bluetape4k-testcontainers`, `bluetape4k-http`, Jackson 3, Micrometer, WireMock, JUnit 5 및 Bluetape 가상 스레드 API/JDK 25 런타임.

---

## 근원 진실

- 사양: `docs/superpowers/specs/2026-07-18-issue-524-planning-contracts-design.md`
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/524
- 분기: `feature/issue-524-planning-contracts`
- 작업 트리: `.worktrees/issue-524-planning-contracts`

## 생태계 역량 선택

| 책임 | 재사용됨 module/capability | 사용되거나 사용되지 않는 이유 | 제약 |
|---|---|---|---|
| 버전 | `bluetape4k-dependencies:1.3.1` | 유일한 Bluetape 권한 | 개별 BOM/version 핀 없음 |
| 지속성 | `bluetape4k-exposed-core`, `bluetape4k-exposed-jdbc` | 저장소 상속 및 감사 가능한 테이블 | 원자 상태 전환에만 적용되는 사용자 정의 SQL |
| 지속성 테스트 | `bluetape4k-exposed-jdbc-tests` | 출시된 JDBC 테스트 도우미 재사용 | 필요한 경우 충돌하는 starter/provider 전이문을 제외하세요 |
| 가상 스레드 | `bluetape4k-virtualthread-api`, 런타임 `bluetape4k-virtualthread-jdk25` | 봄 소유 Java 25 실행 | JDK 21개 공급자 제외 |
| HTTP | `bluetape4k-http` | 프로덕션 VT 클라이언트 | Disable/control 제출 재시도 POST |
| JSON | `bluetape4k-jackson3`, `bluetape4k-exposed-jackson3` | 닫힌 fixture/payload 매핑 | 기본 입력 없음 |
| ID | `bluetape4k-idgenerators` | UUID v7 요청 ID | 공급자 이벤트 ID는 외부에 남아 있습니다 |
| Logging/metrics | `bluetape4k-logging`, `bluetape4k-micrometer` | 맥락과 관찰 | 수정된 낮은 카디널리티 태그만 |
| 인프라 테스트 | `bluetape4k-testcontainers` | PostgreSQL 및 WireMock 실행기 | 기본 CI에는 외부 네트워크가 없습니다 |
| Redis/Kafka/leader/JaVers | 없음 | 첫 번째 계약에는 필요하지 않음 | Redis이 검증된 범위가 되는 경우에만 상추 |

## 파일 구조

아래에 모듈 파일을 만듭니다.
`optimization/planning-contracts/src/main/kotlin/io/bluetape4k/workshop/optimization/planning/`:

- `PlanningContractsApplication.kt`: 애플리케이션 진입점.
- `config/PlanningProperties.kt`: 제한된 공급자, 작업자, 콜백 및 재시도 구성입니다.
- `config/PlanningConfiguration.kt`: 데이터베이스, 실행기, 시계, 엔진 및 라이프사이클 Bean.
- `domain/PlanningModels.kt`: 상태, request/result, 콜백, 감사 및 명령 값.
- `domain/PlanningEngine.kt`: 공급자 중립 submission/status 포트.
- `persistence/PlanningTables.kt`: 집계, 요청, 보낸 편지함, 받은 편지함 및 감사 테이블.
- `persistence/PlanningRecords.kt`: 직렬화 가능한 저장소 프로젝션.
- `persistence/PlanningRequestRepository.kt`: UUID 감사 가능한 저장소와 승인된 개정 업데이트.
- `persistence/PlanningOutboxRepository.kt`: 감사 가능한 긴 저장소와 claim/retry/dead-letter 메소드.
- `persistence/PlanningCallbackInboxRepository.kt`: 감사 가능한 긴 저장소와 없으면 삽입 가능.
- `persistence/PlanningAuditRepository.kt`: 추가 전용 긴 저장소.
- `persistence/PlanningAggregateRepository.kt`: 집계 버전 고정 및 재검증.
- `adapter/fake/DeterministicPlanningEngine.kt`: 기본 고정 장치 어댑터.
- `adapter/http/HttpPlanningEngine.kt`: 공유 정규화된 HTTP 어댑터 구현.
- `adapter/http/TimefoldPlatformPlanningEngine.kt`: 시간 폴더 엔드포인트 매핑.
- `adapter/http/CustomSolverPlanningEngine.kt`: 사용자 정의 분석기 엔드포인트 매핑.
- `adapter/http/CallbackSignatureVerifier.kt`: 프로필별 콜백 확인.
- `application/PlanningRequestService.kt`: 요청 및 발신함 거래.
- `application/PlanningOutboxWorker.kt`: VT claim/submit/retry 처리 중입니다.
- `application/PlanningCallbackService.kt`: 받은 편지함 우선 콜백 트랜잭션.
- `application/PlanningCommandService.kt`: 최종 집계 버전 유효성 검사.
- `application/PlanningQueryService.kt`: 수정된 투영.
- `observability/PlanningObservations.kt`: Micrometer 관찰 및 카운터.
- `web/PlanningDtos.kt`: 검증된 request/callback 및 닫힌 응답 DTO.
- `web/PlanningController.kt`: REST 엔드포인트.
- `web/PlanningExceptionHandler.kt`: 삭제된 오류입니다.

리소스 및 테스트 만들기:

- `optimization/planning-contracts/build.gradle.kts`
- `optimization/planning-contracts/src/main/resources/application.yml`
- `optimization/planning-contracts/src/main/resources/fixtures/fake-planning-result.json`
- `optimization/planning-contracts/src/test/resources/junit-platform.properties`
- `optimization/planning-contracts/src/test/resources/logback-test.xml`
- `optimization/planning-contracts/src/test/resources/fixtures/timefold-submit-response.json`
- `optimization/planning-contracts/src/test/resources/fixtures/custom-solver-submit-response.json`
- 계약, 리포지토리, 작업자에 대한 일치 패키지 테스트
  콜백, HTTP 어댑터, MVC 엔드포인트, 수명 주기 및 재생 다시 시작.
- `optimization/planning-contracts/README.md`
- `optimization/planning-contracts/README.ko.md`
- `optimization/README.md`
- `optimization/README.ko.md`

등록 표면 수정:

- `build.gradle.kts`
- `settings.gradle.kts`
- `AGENTS.md`
- `README.md`
- `README.ko.md`
- `.github/workflows/ci.yml`
- `.github/workflows/Examples.yml`
- `.github/workflows/nightly.yml`
- `scripts/smoke-validate.sh`

## 작업 1: Java 25 최적화 모듈 등록

**복잡성:** 중간
**종속성:** 없음
**패턴 기술:** `bluetape-kotlin-patterns` 모듈 설정

- [ ] Spring으로 `optimization/planning-contracts/build.gradle.kts` 생성
  부팅 MVC, 검증, 액추에이터, Hikari/PostgreSQL, 승인된 Bluetape
  종속성 및 버전 없는 별칭만 해당됩니다.
- [ ] `includeModules("optimization", false, true)` 추가
  `settings.gradle.kts`; 프로젝트 이름이 다음과 같은지 확인합니다.
  `:optimization-planning-contracts`.
- [ ] `targetJavaVersion = 25`만 계산하도록 루트 도구 모음 선택 변경
  `projectDir`이 루트 `optimization/` 아래에 있을 때; Java에 대해 다른 곳에 21을 유지합니다.
  및 Kotlin 툴체인.
- [ ] `io.github.bluetape4k:bluetape4k-virtualthread-jdk21`를 제외합니다.
  최적화 모듈 구성을 수행하고 JDK 25 공급자를 다음과 같이 추가합니다.
  `runtimeOnly`.
- [ ] 필수 테스트 리소스와 최소 애플리케이션 리소스 파일을 추가합니다.
- [ ] `./gradlew projects --console=plain`를 실행하세요.
  예상: `Project ':optimization-planning-contracts'`이(가) 있습니다.
- [ ] `./gradlew :optimization-planning-contracts:javaToolchains --console=plain`을 실행하고
  `:optimization-planning-contracts:compileKotlin`.
  예상: Java 25개의 실행 프로그램이 선택되었으며 컴파일이 성공했습니다.

**Rollback/rerun:** Java 21개 모듈인 경우 toolchain/registration 편집만 되돌리기
대상 25로 해결합니다. 기존 Java 21 모듈 컴파일과 새 모듈을 다시 실행합니다.
계속하기 전에 모듈 컴파일을 진행하세요.

## 작업 2: TDD을 사용하여 도메인 계약 정의

**복잡성:** 중간
**종속성:** 작업 1
**패턴 스킬:** TDD, Kotlin 패턴

- [ ] 결정론적 제출의 경우 `PlanningEngineContractTest`을 먼저 작성하고,
  정규화된 공급자 상태, 안정적인 요청 ID 및 제한된 수정
  설명 값.
- [ ] 집중 테스트를 실행하고 예상되는 컴파일 실패를 관찰하세요.
  `PlanningEngine` 및 계약 값이 존재하지 않습니다.
- [ ] 직렬화 가능한 최소 도메인 값과 `PlanningEngine`을 구현합니다.
  포트. 반복되는 string/long 매개변수에 대해 명명된 값 개체를 사용하고 정의합니다.
  모든 데이터 클래스에 대해 `serialVersionUID`.
- [ ] 기록된 조명기에서 `DeterministicPlanningEngine`을 구현합니다.
- [ ] 녹색이 될 때까지 집중 테스트를 실행합니다. 그런 다음 추가하지 않고 이름을 리팩터링합니다.
  지속성 또는 HTTP 동작.
- [ ] 지원되지 않는 공급자 상태, 부정적인 개정에 대한 failure/edge 테스트를 추가합니다.
  설명이 너무 크고 요청 ID를 알 수 없습니다.

달리다:

```bash
./gradlew :optimization-planning-contracts:test \
  --tests '*PlanningEngineContractTest*' --console=plain
```

예상: RED 생산 유형 전, 그 다음 모든 계약 사례 PASS.

## 작업 3: PostgreSQL 증명을 사용하여 Bluetape Exposed 저장소 구현

**복잡성:** 높음
**종속성:** 작업 2
**패턴 기술:** TDD, `ecc-kotlin-exposed`, Kotlin 테스트

- [ ] 다음을 사용하여 먼저 PostgreSQL 저장소 테스트를 작성하세요.
  `PostgreSQLServer.Launcher.postgres`; 원시 컨테이너를 인스턴스화하지 마십시오.
- [ ] 상속된 요청 CRUD/paging/count 동작 및 추가 전용 감사 확인
  사용자 지정 메서드를 추가하기 전에 삽입합니다.
- [ ] 고유한 `(provider,event_id)` 삽입에 대한 실패한 테스트를 추가합니다.
  전용 발신함 임대 청구, retry/dead-letter 전환, 최신 개정
  업데이트 및 집계 버전 비교.
- [ ] tables/repositories 누락으로 인해 예상되는 RED 실패를 관찰합니다.
- [ ] 다음을 사용하여 테이블 및 레코드 매핑을 구현합니다.
  `UUIDAuditableJdbcRepository`, `LongAuditableJdbcRepository` 및
  `LongJdbcRepository`이 API를 출시했습니다.
- [ ] 테스트 실패로 입증된 원자적 사용자 정의 SQL만 구현하세요. 수입
  최상위 Exposed 연산자와 수신기 섀도잉이 가능한 지역 추출
  발생하다.
- [ ] 출시된 곳에서는 `bluetape4k-exposed-jdbc-tests` fixtures/helpers를 사용하세요.
  API 일치; 오래된 API를 복사하는 대신 비호환성을 기록하세요.
- [ ] 동시 중복 받은편지함 삽입을 위한 `MultithreadingTester` 케이스 추가
  임대 청구 독점권.

순차적으로 실행:

```bash
./gradlew :optimization-planning-contracts:cleanTest \
  --tests '*RepositoryTest*' --tests '*RepositoryConcurrencyTest*' \
  --no-build-cache --max-workers=1 --console=plain
```

예상: 구현 전 각 새로운 동작에 대해 RED, 받은 편지함 행 1개,
한 명의 임대 소유자 및 모든 저장소 케이스 PASS.

**Rollback/rerun:** Drop/recreate 모듈의 격리된 테스트 스키마만 해당됩니다. 절대
실패에 대한 첫 번째 응답으로 공유 Testcontainers 상태를 제거합니다.

## 작업 4: request/outbox 트랜잭션 및 가상 스레드 작업자 구현

**복잡성:** 높음
**종속성:** 작업 3
**패턴 기술:** TDD, Spring Boot Kotlin, performance/stability 스캔

- [ ] 요청 및 보낼 편지함이 커밋되었음을 증명하는 실패한 통합 테스트 작성
  함께 사용하고 보낼 편지함 삽입이 실패하면 둘 다 롤백됩니다.
- [ ] 하나의 만기 행이 청구되고 제출되었음을 증명하는 실패한 작업자 테스트를 작성합니다.
  Bluetape JDK 25 가상 스레드이며 작업 소유 트랜잭션에서 완료됩니다.
- [ ] 제한 시간, 5xx, 클레임 만료, 제한된 재시도에 대한 실패 테스트를 추가합니다.
  배달 못한 편지, 종료 및 복구 다시 시작.
- [ ] 서비스 또는 실행기 구성을 추가하기 전에 RED을 확인합니다.
- [ ] 하나의 Spring 트랜잭션으로 `PlanningRequestService`을 구현하고 UUID
  v7 요청 ID입니다.
- [ ] 다음을 사용하여 Spring 소유 실행기 Bean을 구현합니다.
  `VirtualThreads.executorService()` 런타임 공급자 이름이
  JDK 25개 구현.
- [ ] 제한된 클레임 및 작업자 실행을 구현합니다. JDBC 작품을 제출하지 마세요.
  호출자의 트랜잭션 내부의 다른 실행자.
- [ ] submit/claim 결과를 ​​낮은 카디널리티 logging/observation로 래핑
  문맥; 페이로드를 기록하지 마십시오.

달리다:

```bash
./gradlew :optimization-planning-contracts:test \
  --tests '*PlanningRequestServiceTest*' \
  --tests '*PlanningOutboxWorkerTest*' \
  --max-workers=1 --console=plain
```

예상: 원자 생성, Java 25개의 가상 스레드 실행, 제한된 재시도,
복구 가능한 임대 및 완전한 종료 PASS.

## 작업 5: 콜백 멱등성 및 최종 명령 재검증 구현

**복잡성:** 높음
**종속성:** 작업 4
**패턴 스킬:** TDD, Exposed, Spring Boot Kotlin

- [ ] 승인된 결과, 중복 이벤트에 대한 실패한 콜백 테스트 작성
  잘못된 개정, 변경된 집계 버전, 유효하지 않은 서명 및
  동시 중복 전달.
- [ ] 유효하지 않은 서명이 inbox/audit 행을 생성하지 않고 이벤트를 중복한다고 검증문
  두 번째 승인된 감사 행을 생성하지 않습니다.
- [ ] 집계 버전을 변경하는 실패한 최종 명령 테스트 작성
  콜백 수락 후 충돌 결과가 예상됩니다.
- [ ] 프로필별 `CallbackSignatureVerifier` 구현; HTTP 프로필 사용
  JCE `Mac`에 상수 시간 비교를 더한 반면, 가짜 검증자는 명시적입니다.
- [ ] 하나의 트랜잭션에서 받은 편지함 우선 `PlanningCallbackService`을 구현하고
  중복되지 않은 새로운 콜백에 대한 명시적 결정 감사를 추가합니다.
- [ ] PostgreSQL 집계 상태를 다시 읽으려면 `PlanningCommandService`을 구현하세요.
  사령부 후보 복귀 직전.
- [ ] 녹색이 될 때까지 PostgreSQL을 사용하여 테스트를 순차적으로 실행합니다.

달리다:

```bash
./gradlew :optimization-planning-contracts:test \
  --tests '*PlanningCallbackServiceTest*' \
  --tests '*PlanningCommandServiceTest*' \
  --max-workers=1 --console=plain
```

예상: 중복 수렴, 오래된 거부, 서명 거부 및
집계 버전 충돌 PASS.

## 작업 6: 오프라인 HTTP 어댑터 픽스처 구현

**복잡성:** 높음
**종속성:** 작업 2, 작업 4
**패턴 기술:** TDD, Kotlin HTTP 테스트 규칙

- [ ] Timefold Platform 및 사용자 정의를 위해 먼저 WireMock 계약 테스트를 작성하세요.
  솔버 submit/status 매핑, 시간 초과, 5xx, 잘못된 형식의 JSON, EOF/body 닫기,
  요청 태그 및 수정.
- [ ] `POST` 제출이 자동으로 재시도되지 않음을 증명하는 모호성 테스트를 추가합니다.
  알 수 없는 결과 이후; 상태 조정 또는 명시적 공급자
  재생하기 전에 멱등성이 필요합니다.
- [ ] HTTP 어댑터가 존재하지 않으므로 예상되는 RED을 확인하십시오.
- [ ] 다음을 사용하여 공유 정규화된 HTTP 어댑터를 구현합니다.
  `productionVirtualThreadHttpClientOf`, 명시적 요청 시간 초과, 종료됨
  응답 본문, 제한된 페이로드 및 공급자별 엔드포인트 매퍼.
- [ ] 기본값이 아닌 경우에만 Timefold 및 사용자 정의 솔버 어댑터 등록
  프로필. 기본 테스트에서는 결정적 가짜를 선택하며 API 키가 필요하지 않습니다.

달리다:

```bash
./gradlew :optimization-planning-contracts:test \
  --tests '*HttpPlanningEngineContractTest*' --console=plain
```

예상: 외부 네트워크에 액세스할 수 없고 액세스할 수 없는 모든 로컬 WireMock 사례 PASS
모호한 제출 재시도.

## 작업 7: Spring MVC 및 수정된 읽기 모델 추가

**복잡성:** 중간
**종속성:** 작업 4-6
**패턴 기술:** TDD, 백엔드 구현, Spring Boot Kotlin

- [ ] 생성, 프로세스 데모를 위한 MockMvc/Spring 통합 테스트를 먼저 작성하세요.
  콜백, 쿼리 및 최종 명령 엔드포인트.
- [ ] 공백 ID, 음수 versions/revisions, 대형에 대한 유효성 검사 테스트를 추가합니다.
  본문, 알 수 없는 요청 ID, 잘못된 서명 및 명령 충돌.
- [ ] JSON에 페이로드, 서명,
  비밀, API 키, 스택 추적, JDBC URL, 원시 공급자 본문 또는 수정되지 않음
  설명 필드.
- [ ] 검증된 DTO, 컨트롤러, 쿼리 매핑 및 삭제된 구현
  예외 처리.
- [ ] 생성자 주입을 사용하고 서비스에 비즈니스 로직을 유지하세요.

달리다:

```bash
./gradlew :optimization-planning-contracts:test \
  --tests '*PlanningControllerTest*' --max-workers=1 --console=plain
```

예상: 엔드포인트 success/failure 매핑 및 수정 어설션 PASS.

## 작업 8: 재시작 수렴을 처음부터 끝까지 증명

**복잡성:** 높음
**종속성:** 작업 3-7
**패턴 기술:** TDD, Kotlin 테스트

- [ ] request/outbox 행을 커밋하고 재구성하는 통합 테스트를 작성하세요.
  worker/service 경계는 동일한 공급자 콜백을 다음보다 더 많이 처리합니다.
  한 번 승인된 감사 내역을 검증문합니다.
- [ ] 잘못된 순서의 변형과 집계 버전 변경 변형을 작성합니다.
- [ ] 수명 주기 또는 멱등성 경계가 불완전한 경우 RED을 관찰하세요.
- [ ] 최소한의 복구 수정만 수행하고 모든 저장소, 작업자 및
  작업 3-5의 콜백 테스트.

달리다:

```bash
./gradlew :optimization-planning-contracts:cleanTest \
  --tests '*PlanningRestartConvergenceTest*' \
  --no-build-cache --max-workers=1 --console=plain
```

예상: 재시작과 중복 전달이 정확히 하나로 수렴됩니다.
감사, stale/changed 버전이 거부되었습니다.

## 작업 9: 모듈 문서화 및 검증 표면 등록

**복잡성:** 중간
**종속성:** 작업 1-8
**패턴 기술:** `bluetape-writer`, Kotlin 모듈 설정

- [ ] 소스와 동등한 모듈 그룹 및 모듈 `README.md` 생성 /
  `README.ko.md`은 언어 스위치, 아키텍처, 흐름, 종속성과 쌍을 이룹니다.
  거버넌스, 구성, API 예, 실패 모드 및 집중 명령.
- [ ] 루트 README 로캘 테이블과 `AGENTS.md` 모듈 맵을 업데이트합니다.
  `optimization/` 및 Java 25 예외.
- [ ] `optimization/planning-contracts/**` 경로 트리거와 Java 25를 추가합니다.
  컨테이너 지원 테스트 step/artifact에서 `Examples.yml`까지.
- [ ] 실행기 JDK가 둘 다 프로비저닝할 수 있도록 `ci.yml` 및 `nightly.yml`을 업데이트하세요.
  Java 21개 및 Java 25개의 툴체인; Docker가 없는 연기로부터 모듈을 보호하고
  전체 컨테이너 지원 검증에 포함합니다.
- [ ] 다음을 실행하는 `scripts/smoke-validate.sh`에 `optimization` 그룹을 추가합니다.
  모듈을 `--max-workers=1`과 직렬로 연결합니다.
- [ ] 소비자이므로 publication/BOM 항목이 추가되지 않았는지 확인합니다.
  워크샵 모듈.

달리다:

```bash
./gradlew projects --console=plain
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh optimization
actionlint .github/workflows/ci.yml .github/workflows/Examples.yml .github/workflows/nightly.yml
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs
```

예상 사항: 등록, 작업 흐름 구문, 이중 언어 README 확인 및
컨테이너 지원 최적화 그룹 ​​PASS.

## 과제 10: 최종 검증 및 검토

**복잡성:** 높음
**종속성:** 작업 1-9
**패턴 기술:** 완료 전 검증, 전체 기능 검토

- [ ] 모듈의 전체 테스트를 순차적으로 실행한 다음 하나의 대표 항목을 컴파일합니다.
  혼합 툴체인 경계를 증명하기 위한 기존 Java 21 모듈.
- [ ] 감지 및 `git diff --check`을 실행합니다.
- [ ] HTTP, DB, 실행기, 임대, 콜백에 대해 performance/stability 스캔을 실행합니다.
  및 라이프사이클 파일.
- [ ] 성능, 안정성, 보안, Ops를 통해 최종 차이점을 검토합니다.
  developer/API 및 user/caller 렌즈; P0/P1마다 수정하고 다시 실행하세요.
- [ ] 명명된 통과 테스트에 대해 모든 사양 승인 행을 확인합니다.
- [ ] `docs/review/2026-07-18-issue-524-planning-contracts-review.md` 생성
  그리고 `docs/lessons/2026-07-18-issue-524-planning-contracts.md` 증거와 함께.

순차적으로 실행:

```bash
./gradlew :optimization-planning-contracts:cleanTest \
  --no-build-cache --max-workers=1 --console=plain
./gradlew :exposed-mvc-virtualthread:compileKotlin \
  :optimization-planning-contracts:compileKotlin --console=plain
./gradlew detekt --console=plain
git diff --check
```

예상: 모든 명령 PASS, 최신 검토 P0=0/P1=0, 해결되지 않은 경고 없음 또는
터치된 코드의 지원 중단.

## 사양 추적성

| Spec/issue 요구사항 | 계획 작업 및 증명 |
|---|---|
| Java 최적화 예시 25개 | 작업 1 혼합 툴체인 컴파일; 작업 10 회귀 컴파일 |
| Bluetape VT API + JDK 25개 공급자 | 작업 1 및 4 런타임 공급자 테스트 |
| BOM 1.3.1 제어 Exposed | 작업 1 및 9 종속성 insight/governance 확인 |
| 활성 Exposed 저장소 패턴 | 작업 3 상속된 CRUD 및 원자성 SQL 테스트 |
| PostgreSQLServer 실행기 | 태스크 3, 5, 8 직렬 통합 테스트 |
| 요청 및 보낼 편지함 원자성 | 태스크 4 트랜잭션 롤백 테스트 |
| Duplicate/out-of-order 콜백 동작 | 작업 5 concurrency/revision 테스트 |
| 융합 재시작 | 작업 8 엔드투엔드 복구 테스트 |
| 최종 집계 버전 확인 | 작업 5 명령 충돌 테스트 |
| 결정적 오프라인 가짜 | 태스크 2 계약 테스트 |
| 별도의 공급자 어댑터 | 작업 6 WireMock 계약 테스트 |
| 수정된 읽기 모델 | 작업 7 누수 검증문 |
| 생태계 역량 인벤토리 | 이 계획 테이블과 작업 9 종속성 감사 |
| Module/CI/Nightly/docs 등록 | 작업 9 유효성 검사 명령 |

## 위험 예측

| 위험 | 신호 | 완화 | Rollback/rerun점 |
|---|---|---|---|
| JDK 21개 제공업체 승리 ServiceLoader | 런타임 name/provider 어설션 실패 | JDK 21개 모듈을 제외하고 JDK 25개 runtimeOnly 유지 | 작업 1 compile/runtime 테스트 |
| 중첩된 재시도로 중복된 제출 | 모호한 시간 초과 후 WireMock이(가) 둘 이상의 POST를 봅니다 | 자동 POST 재시도 비활성화; 상태를 먼저 조정 | 작업 6 어댑터 계약 |
| 두 명의 근로자가 한 행을 검증문 | 동시 테스트에서 중복 임대 소유자가 반환됨 | 원자 클레임 조건부 및 임대 토큰 | 작업 3 저장소 테스트 |
| 콜백 감사가 중복됨 | 승인된 감사 수가 1을 초과합니다 | 동일한 거래에 부재 시 고유 받은 편지함 삽입 | 태스크 5/8 융합 테스트 |
| 승인 후 변경사항 집계 | 최종 명령이 여전히 반환됨 | 필수 최신 PostgreSQL 버전 비교 | 작업 5 명령 테스트 |
| Secret/raw 페이로드 누출 | JSON/log 검증문이 금지된 텍스트를 찾았습니다 | 닫힌 응답 DTO 및 정리된 요약 | 작업 7 부정적인 테스트 |
| 컨테이너 수명주기 플레이크 | 재시도 전용 테스트 통과 또는 공유 스키마 충돌 | 직렬 실행 및 격리된 스키마 재설정 | cleanTest에서 작업 3 다시 실행 |
| 종료 시 실행자 누출 | 수명 주기 테스트가 종료되지 않거나 임대가 계속 청구됨 | Spring Bean 파괴 및 복구 가능 TTL | 태스크 4 라이프사이클 테스트 |

## 계획검토 융합

| 렌즈 | 초기계획 발굴 | 계획 수리 | 최신 차단제 |
|---|---|---|---|
| 성과 | 명시적인 제한 batch/body/timeout 증명 없음 | 작업 4, 6, 7 이름 제한 사례 | P0=0, P1=0 |
| 안정성 | 다시 시작, 종료 및 모호한 제출 재생에 순서가 지정된 테스트가 필요함 | 작업 4, 6, 8은 수명 주기 순서를 제공합니다 | P0=0, P1=0 |
| 보안 | HMAC 부정 경로 및 출력 누출 검사가 작업 소유가 아닙니다 | 작업 5와 7은 둘 다 소유 | P0=0, P1=0 |
| Operator/Ops | 혼합 툴체인 CI 및 임대 복구에 명시적인 등록이 필요함 | 작업 9 및 10은 자체 워크플로 및 복구 증명 | P0=0, P1=0 |
| Developer/API | 사용자 정의 DAO 메소드로 저장소 사용을 우회할 수 있음 | 작업 3은 사용자 정의 SQL 이전에 CRUD 상속되었음을 증명합니다 | P0=0, P1=0 |
| User/caller | 계획이 최종적이지 않다는 점을 문서에서 생략할 수 있음 | 작업 9에는 명령 재검증 및 오용 지침이 필요합니다. | P0=0, P1=0 |

통합 검토를 통해 모든 사양 기준이 주문된 작업에 매핑되는지 확인합니다.
작업은 이후 아티팩트에 따라 달라집니다. Testcontainers 작업은 연속적입니다. Exposed 1.2+
가져오기가 보호되고, 공개 KDoc/README 로캘 작업이 할당되고, 롤백됩니다.
도구 체인, 스키마, HTTP 및 수명 주기 위험에 대한 포인트가 있습니다. P0/P1이(가) 남지 않았습니다.

## 정지 조건

- 출시된 BluetapeAPI가 다른 경우 구현을 중지하고 계획을 복구합니다.
  검사된 종속성 bytecode/source에서.
- 없이 자동 재시도를 비활성화할 수 없는 경우 공급자 제출을 중지합니다.
  클라이언트 구성을 교체합니다.
- credentials/public 라우팅 시 실시간 Timefold 배포 증거를 보류 상태로 유지합니다.
  사용할 수 없습니다. 이를 생산 성공으로 시뮬레이션하지 마십시오.
- 현재 승인된 범위에 로컬 문제가 있으므로 PR 생성 전에 중지하세요.
  구현하지만 PR/base/head 생성을 명시적으로 승인하지 않습니다.
  행동.
-  #524이(가) 로컬로 검증된 후 순차적 발행 순서를 유지합니다.
  #532 → #533 → #534. #1055 및  #391는 contract/fixture와 같이 병렬로 계속됩니다.
  해당 시퀀스를 추적하고 차단하지 않습니다.
