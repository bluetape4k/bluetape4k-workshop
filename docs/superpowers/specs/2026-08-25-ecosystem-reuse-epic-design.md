# bluetape4k ecosystem-first 재사용 및 assertions 정비 Epic 설계

- 날짜: 2026-08-25
- 저장소: `bluetape4k/bluetape4k-workshop`
- 기준 분기: `origin/develop`
- 기준 커밋: `7a1a94a5dd636978760d8bc5e1b92bc06656aab4`
- 설계 브랜치: `feat/ecosystem-reuse-gate`
- 상위 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/792
- 마일스톤: `1.4.0`
- 상태: 사용자의 2026-08-25 진행 지시로 실행 승인된 설계

## 문제와 목표

이 저장소는 bluetape4k 라이브러리 사용법을 보여 주는 consumer 예제 모음이다.
그러나 모듈이 이미 의존하는 capability를 다시 구현하거나, 테스트에서
`bluetape4k-assertions` 대신 JUnit/Kotlin raw assertion을 사용하거나, 사용하지
않는 feature dependency를 남기는 경로가 아직 존재한다. 이 상태에서는 예제가
라이브러리의 실제 소비 경계를 가르치지 못하고, 7-Tier review가 같은 문제를
반복해서 발견하게 된다.

이번 작업은 #792를 기준 Epic으로 승격하고, 이미 등록된 후속 이슈를 실제 변경
단위로 묶어 hybrid stacked PR train으로 실행하는 것을 목표로 한다.

### 성공 기준

1. #792가 `Epic`, `difficulty:epic-program`, `enhancement`, `area:governance`
   라벨과 `1.4.0` 마일스톤을 가진 상위 추적 이슈가 된다.
2. #777, #779, #781~#791, #793~#796, #798~#808을 중복 생성하지 않고
   Epic 체크리스트와 train track에 연결한다. #797은 #527 라스트마일 작업이므로
   이 Epic에서 제외한다.
3. 각 PR은 변경 모듈의 source/test anchor, 선택한 bluetape4k capability,
   raw fallback 사유, `bluetape4k-assertions` 적용 범위, 7-Tier 결과를 함께
   제출한다.
4. consumer 프로젝트의 버전은 `bluetape4k-dependencies` BOM에서만 해석하고,
   개별 Bluetape BOM이나 버전 pin을 추가하지 않는다.
5. 각 track은 targeted test, 필요한 Testcontainers 검증, `detekt`,
   `git diff --check`를 통과한 뒤에만 다음 train head로 이동한다.
6. 이번 기반 PR에서는 예제 동작을 바꾸지 않는다. 예제 코드 변경은 child PR의
   테스트 우선 작업으로 분리한다.

## 현재 근거와 범위

| 근거 | 설계에 반영한 사실 |
|---|---|
| Live Issue #792 | 2차 재사용 inventory와 raw fallback gate의 기준 이슈이며, 현재 일반 `refactoring` 이슈다. 후속 목록에는 #793~#796, #798~#808이 있다. |
| Live Issues #777, #779, #781~#791 | Field Service 정확성·수명주기와 repository-wide assertion/Testcontainers 정비가 이미 별도 이슈로 등록되어 있다. 새 이슈를 만들지 않는다. |
| Live Issues #793~#808 | ID, Jackson, HTTP, Lettuce fencing, Money, virtual-thread, R2DBC, dependency hygiene, Exposed 경계의 후속 작업이 열려 있다. |
| `gradle/libs.versions.toml` 및 root `build.gradle.kts` | BOM과 `bluetape4k-assertions`, `bluetape4k-jackson3`, `bluetape4k-idgenerators`, Exposed, Testcontainers 등 alias가 이미 제공된다. |
| `docs/coverage-matrix.md` | assertions가 모든 테스트 모듈에서 Good이라고 기록하지만 legacy assertion 파일이 남아 있어 문서와 소스가 불일치한다. |
| 이전 7-Tier lanes | `bluetape4k-spring-boot-core` HTTP helper와 `bluetape4k-lettuce` fencing을 local raw 구현이 shadow하고, R2DBC integration path가 disabled 상태라는 P1 근거가 있다. |

### 포함 범위

- #792의 Epic metadata, child checklist, train dependency와 acceptance criteria
- 모듈별 ecosystem capability inventory와 raw fallback 분류
- `bluetape4k-assertions` 우선 테스트 규칙과 기존 assertion 이슈 track
- child PR의 7-Tier review, Kotlin pattern, BOM/catalog, workflow/coverage gate
- 실제 코드·테스트·Gradle 정리는 각 child issue의 독립 PR

### 제외 범위

- #527 라스트마일 routing 구현(#797), Kafka recovery Epic(#560), Timefold Epic(#523)
- Bluetape4k library 자체의 API 변경 또는 새 dependency 추가
- 실제 provider credential, 외부 서비스 호출, production schema migration
- PR merge, auto-merge, branch 삭제, release/publication
- raw framework API를 예제의 학습 대상으로 사용하는 경로의 기계적 제거

## 선택지와 결정

### 선택 A — 하나의 대형 PR

모든 assertion, Field Service, HTTP, Redis, R2DBC, fixture 변경을 한 브랜치에
넣는다. 추적은 단순하지만 서로 다른 모듈의 실패가 섞이고, Testcontainers와
동시성 검증을 독립적으로 되돌리기 어렵다. 예제 사이트의 학습 경계를 한 번에
바꾸는 위험도 크므로 채택하지 않는다.

### 선택 B — 완전 독립 PR

모든 child issue를 `origin/develop`에서 분기한다. 충돌은 줄지만 P0 inventory와
assertion 규칙이 각 PR에서 복제되고, 공통 gate가 병합 전까지 일관되지 않을 수
있다. 공통 기반을 소비하는 Field Service 연속 변경도 하나의 학습 흐름으로
읽기 어렵다.

### 선택 C — hybrid stacked PR train (권장)

먼저 하나의 문서·검증 기반 PR(`P0`)을 만들고, 실제 의존성이 있는 모듈만 같은
track 안에서 쌓는다. 서로 무관한 HTTP, Redis, R2DBC track은 모두 `P0`를
base로 삼아 병렬 review가 가능하도록 한다. 각 branch에는 한 domain boundary와
한 책임만 담으며, train의 다음 head는 이전 PR이 정확히 merge된 뒤 갱신한다.

이 선택은 공통 규칙을 한 번만 검증하면서도, assertion migration과
Testcontainers/DB 작업을 독립적으로 재실행할 수 있다. 따라서 이 Epic에는
`stacked`라는 이름을 쓰되, 무관한 모듈을 인위적으로 직렬화하지 않는다.

## PR train 설계

PR 번호는 아직 생성하지 않았으며, 아래 식별자는 train 내부 이름이다. 모든
child branch는 semantic prefix를 사용하고, head/base SHA는 PR 생성 직전에
다시 읽는다.

| 단계 | branch | base | 연결 이슈 | 책임 |
|---|---|---|---|---|
| `P0` | `feat/ecosystem-reuse-gate` | `origin/develop` | #792 | Epic 문서, coverage/inventory contract, train gate |
| `A1` | `test/ecosystem-reuse-assertions-field-service` | `P0` | #783 | Field Service 테스트의 `bluetape4k-assertions`와 bounded concurrency |
| `A2` | `test/ecosystem-reuse-assertions-platform` | `P0` | #785~#791 | aws·commerce·operations·planning·voucher·browser 테스트 assertion/lifecycle 정리 |
| `F1` | `fix/ecosystem-reuse-field-service-contracts` | `A1` | #777, #781, #782, #784 | outbox reason, aggregate CAS, bounded route concurrency, identifier normalization |
| `F2` | `refactor/ecosystem-reuse-field-service-capabilities` | `F1` | #804~#807 | Jackson3 mapper, ID generator, Exposed boundary, unused HTTP dependency |
| `R1` | `refactor/ecosystem-reuse-http-json` | `P0` | #793, #796, #798 | usage billing/Operations JSON mapper와 shared HTTP helper |
| `R2` | `refactor/ecosystem-reuse-identifiers-fixtures` | `R1` | #794, #795 | voucher/ticket fixture ID와 namespace 정책 |
| `T1` | `test/ecosystem-reuse-testcontainers-r2dbc` | `P0` | #779, #802, #803 | Testcontainers wrapper, PostgreSQL R2DBC 활성화, 미사용 dependency 제거 |
| `I1` | `refactor/ecosystem-reuse-fencing-runtime` | `P0` | #799~#801, #808 | Lettuce fencing, Money, virtual-thread lifecycle, bounded Exposed launcher 후보 |

`A1 → F1 → F2`와 `R1 → R2`는 같은 소비 경계를 순차 검토하는 stack이다.
`A2`, `T1`, `I1`은 `P0`에서 병렬 진행할 수 있다. `P0`가 merge되기 전에는
child PR을 merge하지 않고, 각 PR은 자신의 head에 대해 CI와 7-Tier review를
다시 수행한다.

`R1 → R2`의 선행 조건은 fixture가 사용하는 JSON/ID 경계다. `R1`은 usage
billing과 Operations의 mapper/helper 소비를 하나의 canonical serialization
경계로 고정하고, `R2`는 그 경계를 호출하는 voucher/ticket fixture의 V4/V7 및
namespace 정책을 검증한다. manifest의 `parent_evidence`에는 `r1_api_anchor`,
`r1_allowed_path`, `r2_consumer_anchor`, `r2_test_anchor`를 파일·심볼 단위로
기록한다. 따라서 R2의 source/test anchor가 R1의 mapper 또는 fixture factory
API를 호출하지 않으면 R2를 `P0` 기준의 독립 track으로 재분류하고
`parent_track=P0`, `expected_base_ref=<frozen P0 expected_head_ref>`를
원자적으로 갱신하며 `parent_evidence`를 제거한다. P0가
`oid_policy=reviewed-ancestor`이면 `parent_oid`는 P0의
`reviewed_implementation_oid`와 같아야 하며, exact 정책을 사용하는 P0인
경우에만 `head_oid`를 사용한다. reviewed-ancestor의 `base_oid`, `head_oid`,
`merge_base_oid` legacy 필드는 계속 `null`로 유지한다. 이때
`state=PLANNED`, `receipt_status=PENDING`, `receipt_id=null`, `checksum=null`을
유지하고 coordinator receipt에 reparent 사유를 남긴다. 이전 R1 parent를 남긴
채 READY로 올리는 것은 금지한다.

### Train 상태와 무효화

`P0`가 commit된 뒤에는 exact PR base/head/merge-base read-back과 각 child의
parent OID를 receipt와 review artifact에 고정한다. `reviewed-ancestor` manifest는
legacy OID 필드를 `null`로 유지하고, 구현 기준점은
`reviewed_implementation_oid` marker로 고정한다. P0 review 수정, rebase, merge,
revert로 ancestor OID가 바뀌면 해당 ancestor와 모든 descendant를 `INVALID`로
표시하고, 위상 순서로 base/head·targeted test·7-Tier review를 다시 확인한다.
`P0` exact-head CI와 review가 끝나기 전에는 child branch를 다음 단계로 진전시키지
않는다.

terminal receipt 파일은 immutable하다. 재실행은 새 receipt ID/checksum을 만들고
기존 terminal 파일은 보존한 채 node를 `PLANNED/PENDING`으로 되돌린다. 같은
terminal status에서 receipt ID 또는 checksum만 바꾸는 갱신은 허용하지 않는다.

inventory의 `pending → verified` 전환은 병렬 child가 직접 수행하지 않는다.
child PR의 exact-head test receipt와 merge 여부를 받은 뒤 별도 closeout lane이
순차적으로 inventory, Epic checkbox, train 상태를 갱신한다. 따라서 문서 gate와
코드 변경의 상태가 서로 앞서가지 않는다.

train manifest는 `docs/ecosystem-reuse-train.json`에 보관하며 track, expected
head/base ref, parent OID, merge-base, 상태, issue mapping, allowed paths,
structured Gradle/test selectors, timeout, Docker 요구 여부, review artifact,
OID policy, receipt ID, receipt status, checksum을 포함한다. fixed node는
`oid_policy=reviewed-ancestor`를 사용하며 `reviewed_implementation_oid` 필드를
가진다. 최초 manifest에서는 `state=PLANNED`, legacy OID와 reviewed OID가
`null`, receipt가 `PENDING`이어야 한다.

fixed node의 자체 head를 같은 commit 안에 기록할 수 없으므로 coordinator는
review artifact에 `reviewed_implementation_oid: <40-hex SHA>` marker를 남긴다.
marker는 PR base 이후의 구현 ancestor여야 하고 PR head의 선조여야 하며, marker
이후 head까지의 evidence tail은 해당 review artifact 하나만 변경해야 한다.
marker가 PR head와 같거나 비선조이거나 코드 변경을 포함하면 checker가 실패한다.
follow-up scope는 `exact` 또는 `rebase-aware`만 사용한다. workflow coordinator와
serial closeout lane만 manifest를 갱신한다. Issue 본문은 설명과 메타데이터로만
취급하고 child 편집 범위나 shell command를 결정하지 않는다.

독자가 실제 재사용 대상을 바로 찾을 수 있도록 대표 anchor는
[`docs/ecosystem-reuse-inventory.md`](../../ecosystem-reuse-inventory.md)에
고정한다. 예를 들어 `optimization/field-service-dispatch`는
`bluetape4k-assertions`와 `bluetape4k-idgenerators`를 우선 조사하고,
`FieldServiceCallbackEnvelopeTest.kt`와 `FieldServiceCommandServiceTest.kt`에서
source/test evidence를 남긴다. `commerce/usage-metering-billing-event-sourcing`는
`bluetape4k-jackson3`/`bluetape4k-idgenerators`와
`EventCodecRegistryTest.kt`를 연결하며, `operations/job-console-core`는
`bluetape4k-spring-boot4-core`/`bluetape4k-lettuce`와
`JobSubmissionHttpMapper.kt` 및 `JobFencingScriptsTest.kt`를 연결한다. 이 표의
alias·API·assertion·fallback 이유가 없는 변경은 child PR의 acceptance evidence가
될 수 없다.

## 모듈 변경 계약

각 child PR의 본문과 review artifact에는 다음 표를 포함한다.

| 항목 | 필수 내용 |
|---|---|
| Capability | `bluetape4k` alias, 실제 import/API와 선택 이유. inventory의 `actual_import`는 source/test 파일에서 dependency token과 `capability_api` token을 모두 증명해야 하며, dependency declaration만 있거나 현재 도입 전이면 `actual_import=N/A` + `capability_api=candidate: ...`를 사용한다. `libs.*` catalog alias는 API 사용 증거로 인정하지 않는다. |
| Source anchor | 변경 파일과 심볼 또는 line anchor |
| Test anchor | 성공·실패·경계·수명주기/동시성 테스트 |
| Bluetape anchor | released capability는 현재 repository의 실제 adoption import/test anchor와 resolved coordinate를 기록하고, fallback 분류는 upstream anchor를 `N/A`로 둘 수 있다. 모든 행은 local source/test anchor를 유지한다. |
| Assertion | `bluetape4k-assertions` matcher 또는 raw assertion을 유지한 구체적 사유 |
| Classification/Fallback | `released-bluetape4k`, `behavior-under-test`, `provider-gap`, `shared-candidate`, `documented-raw-fallback` 중 하나와 필요한 `fallback_reason` |
| Dependency | 모듈 local declaration인지, BOM에서 해석되는지, 실제 호출이 있는지 |
| 7-Tier | 의미·계약·수명주기·보안·성능·테스트·문서/운영 결과 |

### 분류와 근거 계약

`classification`과 `fallback_reason`은 서로 다른 필드다. 모든 행은 아래 다섯
분류 중 하나를 정확히 선택하고, `source_anchor`와 `test_anchor`를 함께 제공한다.

| 분류 | 선택 기준과 대표 예 | reason 필수 | migration/중단 조건 |
|---|---|---:|---|
| `released-bluetape4k` | BOM에서 바로 사용할 수 있는 `Uuid.V7`, `Jackson.defaultJsonMapper`, `bluetape4k-assertions` | 아니오(선택 이유는 기록) | API가 현재 BOM에서 사라지거나 source/test 호출이 없으면 중단하고 dependency를 제거한다. |
| `behavior-under-test` | HTTP status, JSON node, protocol byte처럼 framework/protocol 자체를 가르치는 raw 검증 | 예 | raw 대상이 학습 목표가 아니게 되면 Bluetape matcher로 전환한다. |
| `provider-gap` | 현재 released Bluetape API가 없어 upstream capability가 필요한 경우 | 예 | upstream API가 안정적으로 출시되면 shadow helper를 제거하고 재검토한다. |
| `shared-candidate` | 두 모듈 이상에서 재사용할 수 있으나 아직 local contract인 helper | 예 | 두 번째 실제 consumer와 compatibility test가 없으면 공통화하지 않는다. |
| `documented-raw-fallback` | 보안·암호·외부 wire contract처럼 raw 구현을 보존해야 하는 경우 | 예 | fallback 이유·negative test·대체 불가 범위 중 하나라도 빠지면 merge를 중단한다. |

`fallback_reason`은 `released-bluetape4k`에서도 선택 이유를 설명할 수 있지만,
나머지 네 분류에서는 비어 있으면 checker가 실패한다. classification은 raw 사용의
허가가 아니라 검토 결과이며, 각 행의 `status=verified`는 exact-head test receipt와
closeout 확인 뒤에만 허용된다.

`bluetape4k-assertions`가 표현할 수 있는 값·예외·문자열·컬렉션 검증에는
raw `assert`, `check`, `assertEquals`, `assertThrows`, `!!`를 새로 추가하지
않는다. framework response status, JSON node, protocol byte, cryptographic
behavior처럼 raw API 자체가 학습 대상이면 해당 테스트에 이유와 대체 불가
범위를 기록한다. 테스트 assertion의 의미가 바뀌지 않도록 migration 전후의
실패 조건과 메시지 계약을 유지한다.

대표적인 migration은 다음처럼 intent를 유지한다.

| 검증 의도 | 기존 raw 형태 | 우선할 Bluetape 표현 | acceptance evidence |
|---|---|---|---|
| 값/컬렉션 | `assertEquals(expected, actual)`, `assertTrue(items.isNotEmpty())` | `actual shouldBeEqualTo expected`, `items shouldNotBeEmpty` 계열 matcher | 같은 실패 입력과 원인 중심 메시지가 유지되는 테스트 |
| 예외 | `assertThrows<IllegalStateException> { ... }` | `assertFailsWith<IllegalStateException> { ... }`; Bluetape 예외 matcher가 실제 BOM에 있음을 receipt로 증명한 경우에만 그 matcher를 사용 | 예외 타입·메시지·cause를 각각 검증하는 negative test |
| 문자열 | `assertTrue(value.contains("token"))` | 문자열 포함/정규식 matcher | 민감한 token 전체를 출력하지 않는 sanitized failure |
| framework/protocol | response status 또는 raw byte 비교 | 학습 목표이면 raw 유지 + `behavior-under-test` 이유 | source anchor, 대체 불가 범위, protocol fixture |

실제 import 가능한 matcher 이름은 각 모듈의 현재 `bluetape4k-assertions`
버전을 먼저 확인해 선택한다. 표의 표현이 해당 버전에 없으면 임의 helper를 만들지
말고 inventory에 provider-gap을 기록한다. 예외 matcher가 없으면 Kotlin 표준
`assertFailsWith`를 raw fallback으로 기록한다. A1/A2는 migration 전후에 같은 실패 입력을
실행하고, Tier 6 review에서 raw assertion 잔여와 allowlist 근거를 함께 확인한다.

각 child의 `docs/review/DATE-TRACK-7tier.md`는 공통 구조를 따른다: `track`,
`reviewed_implementation_oid` marker, manifest checksum, 적용한
`$bluetape-kotlin-patterns` checklist ID, capability/source/test anchor, assertion
migration 또는 fallback 근거, Tier 1~7별 evidence와 finding count, targeted test
receipt, skipped/disabled 정책, owner와 stop condition. PR의 exact
`head_oid`/`base_oid`/`merge_base_oid`는 PR body 또는 별도 외부 receipt에서
read-back하며, committed artifact의 필수 authoritative field로 요구하지 않는다.
`reviewed-ancestor` manifest의 authoritative implementation anchor는 marker와
`reviewed_implementation_oid`이며 legacy OID 필드는 `null`이다. ancestor OID가
바뀌면 영향받은 Tier와 checklist를 다시 실행하고 이전 결과를 PASS로 재사용하지
않는다. 이 구조가 없는 child는 `READY`로 전환할 수 없다.

### Train 용어와 시작 안내

`Track ID`의 `P0/A1/A2/F1/F2/R1/R2/T1/I1`은 stacked PR 단계 이름이고,
`Severity`의 `P0/P1/P2/P3`은 review finding 우선순위다. 같은 문자열 `P0`라도
서로 다른 축이므로 review 표에서는 항상 `Track` 또는 `Severity`를 접두어로 쓴다.

| Track | 대상 독자/선행 조건 | 기준 branch와 첫 명령 | 산출 receipt | 중단 조건 |
|---|---|---|---|---|
| P0 | coordinator; 선행 없음 | `origin/develop`; checker bootstrap | manifest checksum + static report | base drift, unknown receipt, P0/P1 finding |
| A1/A2 | test maintainer; P0 exact-head | P0; module-specific Gradle selector | assertion migration/test receipt | raw matcher 의미 변화, skipped test |
| F1/F2 | Field Service maintainer; A1→F1 | parent head OID 확인 후 selector 실행 | contract + 7-Tier receipt | parent invalid, CAS/lifecycle failure |
| R1/R2 | HTTP/fixture maintainer; R1→R2 | P0 또는 R1 exact head | mapper/ID compatibility receipt | wire/namespace contract drift |
| T1 | DB/Testcontainers maintainer; P0 | Colima/Docker preflight 후 sequential selector | container IDs/logs/cleanup receipt | cleanup residue, disabled integration |
| I1 | runtime/fencing maintainer; P0 | workspace lock 후 sequential selector | fencing/runtime/launcher receipt | epoch/security or provider-gap uncertainty |

## 실패 모드와 대응

1. **Assertion 의미 변화** — null/exception/message matcher가 달라져 테스트가
   약해질 수 있다. 기존 실패 입력을 먼저 고정하고 intent-specific matcher와
   `assertFailsWith` 등 허용된 Bluetape 표현으로 교체한다.
2. **전이 의존성에 숨은 feature 사용** — root 전역 주입으로 compile만 되는
   모듈이 생길 수 있다. 모듈 alias, source import, test anchor를 함께 검사하고
   실제 feature가 없으면 dependency를 제거한다.
3. **Stack drift 또는 잘못된 base** — 선행 PR merge 후 branch가 stale해질 수
   있다. 다음 PR 전 exact base/head와 merge-base를 읽고, drift가 있으면
   rebase/force-push를 진행하지 않고 train을 멈춰 재검증한다.
4. **Testcontainers/DB 수명주기 실패** — disabled test를 green으로 오인하거나
   container를 병렬 실행해 fixture가 오염될 수 있다. `TestMutexService`, 실제
   PostgreSQL/Redis profile, sequential command와 artifact를 필수로 한다.
5. **Raw fallback 과잉 제거** — 예제가 가르치려는 framework/protocol 동작을
   지울 수 있다. 자동 교체 전에 classification과 test intent를 확인하고
   allowlist에 사유를 기록한다.
6. **외부 상태의 동시 갱신** — Issue body·label·milestone·comment를 순차
   갱신하는 동안 다른 사용자가 수정할 수 있다. mutation 전 `{title, labels,
   milestone, body, updatedAt, body hash}`를 저장하고 mutation 직전 재조회하며,
   drift가 있으면 중단한다. comment는 stable marker로 멱등 처리하고, 각 단계의
   전체 read-back receipt 없이는 다음 단계를 실행하지 않는다.
7. **검증 스크립트의 경로 탈출** — inventory가 외부 absolute path, `../`,
   symlink 또는 control character를 포함할 수 있다. checker는 `Path.resolve()`
   후 repository root containment를 확인하고, 외부·symlink·NUL/newline을
   fail closed로 거부한다. 출력은 shell에서 재실행하지 않는 비민감 report만 남긴다.
8. **검증 workflow 권한 확대** — fork PR의 checker가 write token이나 secret을
   받으면 inventory/report가 공격 입력 경계가 된다. P0 gate는 `contents: read`
   만 허용하고 checkout credential을 보존하지 않으며, 새 action reference를
   commit SHA로 고정하고 report를 실행하지 않는다.

## 호환성·롤백

consumer 예제의 내부 구현과 테스트만 변경하므로 public bluetape4k API나 외부
wire contract는 원칙적으로 유지한다. ID version, mapper defaults, Money rounding,
fencing epoch처럼 의미가 바뀌는 항목은 각 child PR에서 compatibility test와
명시적 migration note를 추가한다. 문제가 생기면 해당 PR만 revert하고, 선행
stack을 되돌리지 않은 상태에서 후속 branch를 재생성하지 않는다.

## Epic 수용 기준과 DoD

- [ ] #792가 Epic metadata와 `1.4.0` milestone을 가진다.
- [ ] 모든 포함 issue가 체크리스트와 train track에 연결되고, #797 등 제외
      범위가 명시된다.
- [ ] `docs/coverage-matrix.md`와 새 inventory 문서가 같은 capability·fallback
      분류를 가리킨다.
- [ ] `P0`의 문서/검증 gate가 재현 가능하고, child PR은 실제 source/test anchor를
      제출한다.
- [ ] 모든 변경 모듈에서 Kotlin pattern과 7-Tier review가 PASS하고,
      P0/P1 finding이 없는 상태로 수렴한다.
- [ ] `P0` exact-head CI/review와 base/merge-base receipt가 고정되고, ancestor
      변경 시 descendant가 `INVALID`로 되돌아가는 규칙이 검증된다.
- [ ] `bluetape4k-assertions` migration 대상의 raw assertion 잔여는 이슈 또는
      allowlist 근거가 있다.
- [ ] inventory 상태는 closeout lane이 exact-head test receipt와 merge 여부를
      확인한 뒤에만 `verified`로 전환한다.
- [ ] 각 PR의 targeted test, 필요한 container test, `detekt`, `git diff --check`
      결과가 exact head에 대해 기록된다.
- [ ] merge/auto-merge는 별도 fresh approval 전까지 PENDING이다.

## 추적 가능한 미결 사항

- upstream `bluetape4k`의 새 API가 필요한 경우에는 먼저 `provider-gap`으로
  분류하고, workshop 안에 임시 shadow helper를 만들지 않는다.
- `build-logic`의 consumer가 아닌 테스트 assertion은 이 Epic의 자동 migration
  범위가 아니며, 별도 low-priority 기록을 유지한다.
- 현재 `origin/develop`는 기준 커밋과 일치하지만, PR 생성·검토·merge 직전에
  GitHub live head와 CI를 다시 확인한다.
