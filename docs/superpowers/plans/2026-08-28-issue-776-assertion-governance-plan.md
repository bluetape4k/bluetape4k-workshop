# #776 assertion matcher governance 구현 계획

> 이 계획은 #742 AWS settings child exact head 위에 쌓이는 terminal child의
> 구현·검증 순서를 고정한다. 중간 PR 승인이나 merge는 하지 않는다.

## 목표

consumer Kotlin 테스트의 generic Boolean/null equality를
`bluetape4k-assertions` 의도 기반 matcher로 전환하고, legacy import와 같은
matcher 회귀를 CI에서 즉시 차단하는 deterministic static guard를 제공한다.

## 작업 순서

### 1. Contract와 RED 테스트

- [x] `check-assertion-governance.py`의 scan/result contract를 테스트로 고정
- [x] legacy import, generic Boolean/null matcher 검출 테스트 작성
- [x] build-logic allowlist, framework DSL, 주석·문자열, 일반 equality의
      non-finding 테스트 작성
- [x] guard 미구현 상태에서 RED 결과를 읽고 기록

### 2. Guard GREEN 구현

- [x] 기존 ecosystem checker의 comment/string masking helper 재사용
- [x] `src/test`와 `src/testFixtures` 범위, 파일·행별 deterministic report 구현
- [x] build-logic allowlist와 consumer-only 실패 규칙 구현
- [x] guard unit test GREEN 및 현재 baseline scan 실행

### 3. Matcher migration

- [x] 10개 consumer 테스트 파일의 Boolean/null 비교 19개를
      `shouldBeTrue`/`shouldBeFalse`/`shouldBeNull`로 전환
- [x] import 정리와 numeric/string/domain equality 보존
- [x] 변경 모듈 targeted test와 compile을 순차 실행

### 4. CI·문서·등록

- [x] ecosystem reuse workflow path와 guard test/실행 step 추가
- [x] `scripts/smoke-validate.sh`에 assertion-governance 검증 경로 추가
- [x] coverage matrix, Korean lesson, 7-Tier review artifact 작성
- [x] workflow YAML/actionlint, `py_compile`, `git diff --check` 실행

### 5. Stacked scope·PR publication

- [x] `docs/ecosystem-reuse-train.json`에 `assertion-governance-followup`
      scope와 fresh coordinator receipt 추가
- [ ] #742 exact head를 base로 local scope checker PASS
- [ ] terminal PR 생성, issue milestone/assignee/labels/body/DoD live read-back
- [ ] hosted CI와 exact head를 확인하고 전체 train closeout만 PENDING으로 유지

## 검증 명령

```bash
python3 .github/scripts/test_check_assertion_governance.py -v
python3 .github/scripts/check-assertion-governance.py
./gradlew :operations-job-console-core:test :optimization-field-service-dispatch:test \
  :optimization-shift-coverage:test :optimization-clinic-appointment-solver:test \
  :commerce-concert-ticket-flash-sale:test \
  :commerce-event-sourced-promotion-voucher-campaign:test \
  :shared:test --no-build-cache --console=plain --max-workers=1
./gradlew detekt --no-build-cache --console=plain --max-workers=1
./gradlew projects --console=plain
./scripts/smoke-validate.sh assertion-governance
git diff --check
```

## 위험과 중단 조건

- guard가 framework DSL이나 문자열을 오탐하면 구현을 멈추고 RED regression
  case를 먼저 추가한 뒤 masking/패턴 범위를 좁힌다.
- matcher 함수가 해당 타입을 지원하지 않으면 generic equality를 억지로
  바꾸지 않고 issue inventory에 근거를 남긴다.
- Testcontainers 모듈은 동시에 실행하지 않고 순차 검증한다.
- manifest scope가 기존 fixed track과 충돌하면 allowlist를 넓히지 말고
  stacked scope의 expected base/head와 coordinator receipt를 먼저 고친다.
- hosted CI 실패 또는 exact head drift가 있으면 PR merge 단계로 진행하지
  않는다.

## Plan DoD

- [x] SPW-01 — 대상 파일·명령·수용 기준·stop condition을 고정했다.
- [x] SPW-02 — 의존 순서, RED/GREEN, 파일 범위, rollback/재실행 지점을
      명시했다.
- [x] SPW-03 — 한국어 계획과 보존해야 할 코드 token을 검토했다.
- [x] SPW-04 — #776 issue, 현재 scan, 기존 train/checker contract와 연결했다.
- [x] SPW-05 — 실행 전 plan을 read-back했다.
