# F1 (#781/#784) Field Service 후속 7-Tier 검토

## 검토 범위와 판정

이번 F1 후속 lane은 이미 통합된 Field Service production 계약에 대해 aggregate
CAS와 식별자 정규화의 누락된 회귀 검증을 보강한다. production source, manifest,
inventory는 변경하지 않았으며, 새 검증과 이 문서만 수정했다.

현재 판정은 **P0=0, P1=0, P2=0 / IMPLEMENTATION VERIFIED**다. 기존 P2
follow-up의 범위를 넓히지 않고 #781과 #784의 수용 기준만 검증한다.

## 이슈별 수용 증거

| 이슈 | 수용 기준 | 구현·테스트 증거 |
|---|---|---|
| #781 | 동일 aggregate의 동일 expected version append 동시성에서 정확히 하나만 성공 | `FieldServiceCasIntegrationTest`의 CAS 동시성 검증이 기존 production repository 계약을 재확인한다. |
| #784 | value-class ID 입력은 trim·검증된 값을 보존하고 저장/codec round-trip에서도 정규화 유지 | `FieldServiceCanonicalizerTest`와 `FieldServiceCasIntegrationTest`가 `WorkerId`, `VisitId`, `PlanId`, `AggregateId`, `CoordinateId`, `Skill`, `DatasetId`, `ProviderRequestId`를 검증한다. |

## Bluetape Kotlin pattern 정렬

- 기존 `bluetape4k.assertions` matcher와 repository/codec helper를 재사용했다.
- value-class의 validated `invoke`와 null-safety 계약을 변경하지 않았다.
- 새 dependency, 개별 Bluetape BOM, 중복 abstraction을 추가하지 않았다.

## 7-Tier 결과

| Tier | 판정 | 확인 내용 |
|---|---|---|
| 1. 의미·도메인 | PASS | ID normalization과 aggregate expected-version CAS의 기존 도메인 의미를 그대로 유지했다. |
| 2. 정확성·계약 | PASS | 모든 변경 대상 ID 타입의 canonical value와 persisted record round-trip을 검증했다. |
| 3. 수명주기·동시성 | PASS | 동일 expected version append에서 단일 성공 계약을 full module test로 재확인했다. |
| 4. 보안·비밀 | N/A | 이번 lane은 secret/error 처리와 저장 payload를 변경하지 않았다. |
| 5. 성능·자원 | PASS | production 경로를 수정하지 않고 기존 bounded repository/codec 경계를 재사용했다. |
| 6. API·유지보수 | PASS | 기존 public constructor·validated ID API와 Bluetape assertion convention을 유지했다. |
| 7. 운영·검증 | PASS | 의도적인 assertion 실패를 수정한 뒤 targeted·full module test와 diff check를 완료했다. |

## Fresh verification receipt

```text
RED: 초기 round-trip 검증은 worker name의 공백을 ID payload로 오인하는
assertion으로 실패했다 (`Expected <true> to be <false>`).
GREEN: `:optimization-field-service-dispatch:test --tests
io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceCasIntegrationTest.validated\ identifiers\ remain\ normalized\ through\ persisted\ record\ round\ trips`
— 1 test passed.
GREEN/FULL: `:optimization-field-service-dispatch:test` — 76 tests passed,
failures=0, errors=0, skipped=0; `BUILD SUCCESSFUL`.
STATIC: `./gradlew detekt --no-daemon` — `BUILD SUCCESSFUL`; `git diff --check` — PASS.
BASE/HEAD: current branch is prepared on coordinator exact head
`67b393c6` (the post-merge `develop` base) with implementation head
`9b1c6c20` before the review-artifact commit.
```

## 남은 게이트와 DoD

- [x] #781 aggregate CAS regression coverage
- [x] #784 identifier normalization and persisted round-trip coverage
- [x] 7-Tier review artifact 및 fresh verification receipt
- [ ] PR metadata·hosted CI·review/thread read-back, final fresh approval,
  rebase merge, canonical sync — coordinator closeout

현재 상태: **F1 FOLLOW-UP IMPLEMENTATION VERIFIED / COORDINATOR CLOSEOUT PENDING**.
