# Leader Job Safety Lab Visual Companion 작업계획

## 1. 문서 상태

- 상태: 승인됨
- 승인일: 2026-07-30
- 작업 유형: Type E 문서 작업
- 기준 브랜치: `develop`
- 기준 커밋: `39616246e2f03a3b332bbcc40bc04cd7e03c9dad`
- 대상 예제: `leader/job-safety-lab`
- 공개 범위: 한국어와 영어 Visual Companion
- 사용자 검토 지점: 로컬 브라우저 검증 완료 후, 커밋과 PR 생성 전

이 계획은 대화가 다른 주제로 전환되더라도 작업 범위와 완료 조건을 유지하기 위한
체크포인트다. 애플리케이션 코드는 변경하지 않는다.

## 2. 독자가 이해해야 할 핵심

Visual Companion은 다음 질문에 답해야 한다.

1. Leader 선출에 성공했는데도 이전 작업자의 데이터 변경이 반영될 수 있는 이유는 무엇인가?
2. 리스가 만료된 이전 작업자와 새 작업자가 동시에 실행되면 어떤 결과가 발생하는가?
3. Redis가 발급한 펜싱 토큰(fencing token)을 PostgreSQL이 다시 검사해야 하는 이유는 무엇인가?
4. 데이터베이스 변경과 외부 시스템 호출은 왜 서로 다른 방식으로 보호해야 하는가?
5. `SAFE` 구현은 여섯 가지 운영 시나리오에서 `UNSAFE` 구현과 어떻게 다른가?
6. 실제 예제를 어떤 순서로 실행하고 어떤 결과를 확인해야 하는가?

## 3. 근거 자료

### 설명과 설계

- `leader/job-safety-lab/README.ko.md`
- `leader/job-safety-lab/README.md`
- `docs/superpowers/specs/2026-07-22-issue-548-job-safety-lab-design.md`
- `docs/superpowers/plans/2026-07-22-issue-548-job-safety-lab-plan.md`
- `docs/lessons/2026-07-22-issue-548-job-safety-lab.md`

### 실제 구현

- `coordination/JobRunCoordinator.kt`
- `coordination/redis/RedisJobFencingLeaseAdapter.kt`
- `execution/FencedJobExecutionService.kt`
- `persistence/JobSafetyJdbcExecutor.kt`
- `persistence/JobSafetyRepositories.kt`
- `effect/OutboxEffectWorker.kt`
- `scenario/JobSafetyScenario.kt`
- `scenario/JobSafetyScenarioService.kt`
- `scenario/UnsafeScenarioAdapter.kt`

### 검증 근거

- `FencedMutationPostgresIntegrationTest.kt`
- `JobSafetyEndToEndIntegrationTest.kt`
- `JobSafetyContextRestartIntegrationTest.kt`
- `LeaseOverrunScenarioTest.kt`
- `CrossJobCollisionScenarioTest.kt`
- `DynamicTenantScenarioTest.kt`
- `RegionPartitionScenarioTest.kt`
- `MixedVersionRolloutScenarioTest.kt`
- `NonFenceableEffectScenarioTest.kt`
- `ExternalEffectRecoveryIntegrationTest.kt`
- `OutboxEffectWorkerTest.kt`
- `UnsafeJobSafetyControllerConditionTest.kt`

보이는 단계, 분기, 상태와 결과는 위 근거에서 확인된 동작만 사용한다.

## 4. 예제 구상과 문제 파악

### 4.1 문제를 한 문장으로 정의

리스가 만료된 이전 작업자가 다시 실행되더라도 최신 작업 결과만 PostgreSQL에
반영되어야 한다.

### 4.2 구분해서 설명할 보장

| 보장 | 이 예제에서 담당하는 구성 요소 |
| --- | --- |
| 동시 실행 제한 | Redis Leader 리스와 자원 리스 |
| 작업 인계 | 리스 만료 후 새 작업자의 리스 획득 |
| 지연된 데이터 변경 차단 | 단조 증가하는 펜싱 토큰과 PostgreSQL 조건부 갱신 |
| 재실행 안전성 | 안정적인 `OperationId`, 외부 시스템의 멱등 처리, Receipt 고유 키 |
| 처리 결과 영속화 | 업무 상태, Checkpoint, 실행 이력, Outbox를 하나의 Exposed 트랜잭션으로 반영 |
| 외부 처리 복구 | Outbox, 원래 `OperationId` 조회, Receipt 확인, Reconciliation |

Leader 선출, 리스, 펜싱, 데이터베이스 반영, 외부 시스템 호출을 하나의 보장으로
설명하지 않는다.

### 4.3 시나리오를 구성한 이유

| 시나리오 | 확인할 실패 조건 |
| --- | --- |
| `CROSS_JOB_COLLISION` | 이름이 다른 두 작업이 같은 업무 자원을 동시에 변경한다. |
| `LEASE_OVERRUN` | 이전 작업자의 리스가 만료된 뒤 새 작업자가 작업을 인계한다. |
| `DYNAMIC_TENANT` | 실행 도중 Tenant 구성이 변경된다. |
| `REGION_PARTITION` | 작업자가 현재 처리 대상 Region과 다른 위치에서 실행된다. |
| `MIXED_VERSION_ROLLOUT` | 이전 배포 버전의 작업자가 데이터 변경을 시도한다. |
| `NON_FENCEABLE_EFFECT` | 데이터베이스 펜싱으로 되돌릴 수 없는 외부 처리가 발생한다. |

이 표는 Visual Companion을 만들기 위해 예제를 선택한 이유가 아니다. 예제 자체가
운영 문제를 재현하기 위해 각 시나리오를 구성한 이유를 설명한다.

## 5. 해결 방안 도출

### 5.1 검토하고 제외한 접근

| 접근 | 제외한 이유 |
| --- | --- |
| Leader 리스만 사용 | 리스가 만료된 이전 작업자의 재실행과 데이터 변경을 차단하지 못한다. |
| Leader Owner Token을 펜싱 토큰으로 재사용 | Owner Token은 순서를 비교할 수 없는 식별자다. |
| Redis에서 최종 반영 여부까지 결정 | Redis 리스 상태와 PostgreSQL 업무 상태 사이에 일관성 차이가 발생할 수 있다. |
| 외부 호출 실패 시 새로운 요청 ID로 재시도 | 첫 번째 호출이 실제로 반영됐는지 알 수 없으면 중복 처리가 발생할 수 있다. |

### 5.2 채택한 방식

1. Redis Lua가 충돌 자원별로 단조 증가하는 펜싱 토큰을 발급한다.
2. PostgreSQL은 `incomingFence > lastAcceptedFence`일 때만 데이터 변경을 반영한다.
3. 업무 상태, Checkpoint, 실행 이력, Outbox는 하나의 Exposed 트랜잭션에서 반영한다.
4. 외부 처리에는 안정적인 `OperationId`를 사용한다.
5. 처리 결과가 불확실하면 새로운 요청을 보내기 전에 원래 `OperationId`를 조회한다.
6. Receipt를 확인한 뒤 해당 실행을 `COMPLETED`로 전환한다.

## 6. Visual Companion 구성

한국어 제목:

> 리스가 만료된 이전 작업자가 다시 실행돼도 최신 작업 결과만 PostgreSQL에 반영된다

영어 제목:

> Only the newest worker result reaches PostgreSQL after an expired worker resumes

### 6.1 설명 순서

1. 예제 구상과 문제 파악
2. 해결 방안 도출
3. 설계 방향
4. 구현 방향
5. 대화형 시스템 흐름
6. 실제 구현과 테스트
7. 예제 실행 방법
8. 해결하는 기술적 문제와 적용 한계

### 6.2 대화형 UI

`#simulation` 영역은 다음 조작을 제공한다.

- 여섯 가지 시나리오 선택
- `SAFE`와 `UNSAFE` 비교
- 이전 단계, 다음 단계, 처음부터 재생
- 현재 단계의 설명과 적용 중인 안전 장치 표시
- 작업자 A, Redis, 작업자 B, PostgreSQL, Outbox/외부 시스템 상태 표시
- 최종 업무 상태, 실행 상태, 외부 처리 횟수 표시

기본 화면은 `LEASE_OVERRUN`의 `SAFE` 모드다.

`LEASE_OVERRUN`은 다음 흐름을 보여준다.

1. 작업자 A가 펜싱 토큰 `41`을 받고 실행을 시작한다.
2. 작업자 A의 처리가 지연되어 리스가 만료된다.
3. 작업자 B가 펜싱 토큰 `42`를 받고 최신 결과를 PostgreSQL에 반영한다.
4. 작업자 A가 다시 실행되어 토큰 `41`로 반영을 시도한다.
5. PostgreSQL이 작업자 A의 변경을 `STALE_FENCE`로 거부한다.

### 6.3 시각 구분

| 영역 | 색상 역할 |
| --- | --- |
| API | 요청과 실행 모드 |
| 작업 조정 | Leader 리스, 자원 리스, 펜싱 토큰 |
| 데이터베이스 | 조건부 갱신, Checkpoint, 실행 이력, Outbox |
| 외부 처리 | Provider 호출, Receipt, Reconciliation |

색상만으로 상태를 구분하지 않는다. 아이콘, 상태명, 테두리와 설명을 함께 사용한다.

### 6.4 Architecture Diagram

정적 Architecture Diagram은 실제 구현 구성 요소와 연결 관계를 다음 순서로 보여준다.

1. 호출자가 `JobSafetyController`를 통해 실행을 요청한다.
2. `JobRunCoordinator`가 Redis의 Leader 리스와 자원별 펜싱 토큰을 획득한다.
3. `FencedJobExecutionService`가 현재 구성과 펜싱 토큰을 다시 확인한다.
4. PostgreSQL과 Exposed가 업무 상태, Checkpoint, 실행 이력, Outbox를 하나의
   트랜잭션으로 반영한다.
5. `OutboxEffectWorker`가 커밋된 Outbox를 가져와 외부 시스템에 안정적인
   `OperationId`로 요청한다.
6. 처리 확인 정보와 결과 재확인 상태를 PostgreSQL에 기록한다.

Diagram은 한국어와 영어 문서에서 각 언어의 설명 라벨을 사용한다. 구성 요소명,
저장소명, 상태명과 코드 식별자는 원문을 유지한다. 동일한 SVG 구조가 Light
Theme과 Dark Theme의 색상 토큰을 사용하도록 구현한다.

## 7. 한국어 기술문서 기준

- `authority`를 `권위`로 번역하지 않는다. 문맥에 따라 `최종 반영 기준`,
  `상태 변경 책임`, `판정 책임`, `기준 데이터 저장소`로 설명한다.
- 실행에 필요한 PostgreSQL과 Redis는 `의존 서비스`가 아니라 `관련 서비스`로 쓴다.
- 일반 설명에서는 `worker`를 `작업자`, `lease`를 `리스`, `layer`를 `계층`으로 쓴다.
- 처음에는 `펜싱 토큰(fencing token)`으로 쓰고 이후에는 `펜싱 토큰`으로 통일한다.
- `늦은 작업자` 대신 `처리가 지연된 작업자`를 사용한다.
- 코드 식별자, 클래스, 메서드, 상태 코드, Profile, API 경로는 원문을 유지한다.
- 추상적인 선언형 제목이나 홍보 문구를 사용하지 않는다.
- 문제, 실제 동작, 결과, 적용 조건을 구체적인 동사로 설명한다.

## 8. 구현 파일

- 추가:
  - `scripts/generate-leader-job-safety-visual-companion.mjs`
  - `docs/superpowers/specs/2026-07-30-leader-job-safety-lab-visual-companion.html`
  - `docs/superpowers/specs/2026-07-30-leader-job-safety-lab-visual-companion.en.html`
- 변경:
  - `docs/visual-companions/manifest.json`
- 검토 후 필요할 때만 변경:
  - `scripts/validate-visual-companions.mjs`

기존 애플리케이션 코드와 테스트 코드는 변경하지 않는다.

## 9. 검증 계획

### 9.1 정적 검증

- `node scripts/validate-visual-companions.mjs`
- `git diff --check`
- 한국어와 영어 문서의 시나리오, 상태, 링크, 숫자, 클래스명 비교
- 한국어와 영어 Architecture Diagram의 구성 요소와 연결 관계 비교
- 외부 스크립트, 외부 스타일, 네트워크 요청이 없는지 확인
- 설계문서 링크와 기준 커밋 확인

### 9.2 브라우저 검증

- 한국어와 영어
- Light Theme과 Dark Theme
- 데스크톱과 모바일 Viewport
- 여섯 가지 시나리오
- `SAFE`와 `UNSAFE`
- 키보드 포커스와 조작
- 텍스트 잘림, 요소 겹침, 가로 스크롤
- Architecture Diagram의 연결선, 화살표, 라벨과 색상 구분
- 브라우저 콘솔 오류

시각 검증용 스크린숏은 `output/playwright/`에 만들며 커밋하지 않는다.

## 10. 완료 조건

- [x] README, 설계문서, 계획문서, 교훈 문서, 실제 소스와 테스트에서 근거를 확인했다.
- [x] 구상 단계(Brainstorming)가 문제 정의, 대안 검토, 해결 방안 도출 순서로 포함됐다.
- [x] 여섯 시나리오와 `SAFE`/`UNSAFE` 차이를 대화형 UI에서 확인할 수 있다.
- [x] `LEASE_OVERRUN`의 펜싱 토큰 `41`과 `42` 흐름이 실제 구현과 일치한다.
- [x] 실제 클래스, 테스트, 실행 명령과 API 경로를 확인할 수 있다.
- [x] 한국어 문장이 `bluetape-writer` 기술문서 기준을 충족한다.
- [x] 영어 문서가 한국어 문서와 동일한 기술 내용을 제공한다.
- [x] Light/Dark Theme과 모바일/데스크톱 화면을 검증했다.
- [x] Architecture Diagram이 한/영 및 Light/Dark Theme에서 같은 구조와 의미를 제공한다.
- [x] Visual Companion 검증 스크립트와 `git diff --check`가 통과했다.
- [x] 로컬 서버 URL에서 사용자가 결과를 검토할 수 있다.

## 11. 로컬 검증 결과

- Visual Companion 검증:
  - `node scripts/validate-visual-companions.mjs`
  - 결과: `3 documents / 6 locale files`
- 시나리오 테스트:
  - `./gradlew :leader-job-safety-lab:test --tests 'io.bluetape4k.workshop.leader.jobsafety.scenario.*'`
  - 결과: 9개 테스트 통과
- 브라우저 검증:
  - 한국어 Light Theme / 데스크톱
  - 한국어 Dark Theme / 모바일
  - 영어 Light Theme / 모바일
  - 영어 Dark Theme / 데스크톱
  - 네 조합에서 Architecture Diagram의 구성 요소, 연결선, 화살표와 라벨 확인
  - Architecture Diagram 카드 라벨 Overflow 0건
  - Light/Dark Theme별 API, 작업 조정, 데이터베이스, 외부 처리 색상 전환 확인
  - 각 화면에서 여섯 시나리오의 `SAFE`/`UNSAFE` 12개 경로 확인
  - 콘솔 오류 0건, 화면 전체 가로 Overflow 0건
  - 동일 입력의 전체 화면 캡처 Hash 일치
- 로컬 검토 URL:
  - `http://127.0.0.1:4317/docs/superpowers/specs/2026-07-30-leader-job-safety-lab-visual-companion.html`
  - `http://127.0.0.1:4317/docs/superpowers/specs/2026-07-30-leader-job-safety-lab-visual-companion.en.html`

## 12. 중단 지점과 후속 작업

1. 로컬 시각 검증이 끝나면 서버 URL과 검토 항목을 사용자에게 제공한다.
2. 사용자 검토가 끝나기 전에는 커밋, Push, PR 생성을 진행하지 않는다.
3. PR을 생성하더라도 병합은 최신 Head와 CI 결과를 다시 확인한 뒤 별도 승인을 받는다.
4. `Kafka Outbox Fallback` Visual Companion은 이 자료의 검토가 끝난 뒤 별도 계획으로 진행한다.
