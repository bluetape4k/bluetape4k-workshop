# A2 (#785~#791) assertions·logging 후속 7-Tier 검토

## 검토 범위와 판정

A2 후속 lane은 Kinesis lifecycle logging을 Bluetape lazy logging contract로
정렬하고, 허용된 테스트 앵커의 plain assertion을 Bluetape assertion helper로
통일한다. framework DSL, fixture, manifest, inventory는 변경하지 않았다.

현재 exact head `2652884d`는 최신 `develop` `b5a09c4b` 위에 rebased 되었고,
변경 파일은 manifest allowlist 안의 22개 source/test와 이 review artifact다.
판정은 **P0=0, P1=0, P2=0 / IMPLEMENTATION VERIFIED**다.

## 이슈별 수용 증거

| 이슈 | 수용 기준 | 구현·테스트 증거 |
|---|---|---|
| #785 | Kinesis lifecycle logging을 lazy Bluetape logging으로 전환 | `KinesisDemoRunner`와 `KinesisShutdownConfiguration`이 `KLoggingChannel`을 사용하고 LoggerFactory/raw logger 패턴을 제거했다. |
| #786~#790 | 허용된 테스트에서 예외·값·문자열 assertion의 의미를 Bluetape matcher로 보존 | voucher, metering, operations, planning, order lifecycle 테스트의 JUnit/raw assertion을 `assertFailsWith`, `shouldBeEqualTo`, `shouldContain`, `shouldNotContain` 등으로 전환했다. framework DSL과 HTTP/JSON 계약은 그대로 유지했다. |
| #791 | 기존 A2 ancestor 변경과 test contract를 유지 | leader/job-console canonicalizer·policy·bounded connection 테스트를 재검증하고 새 dependency나 production API를 추가하지 않았다. |

## 7-Tier 결과

| Tier | 판정 | 확인 내용 |
|---|---|---|
| 1. 의미·도메인 | PASS | Kinesis lifecycle message와 voucher/HTTP/JSON contract의 의미를 보존했다. |
| 2. 정확성·계약 | PASS | 예외 타입, matcher predicate, redaction·cache·accessibility 문자열 계약을 유지했다. |
| 3. 수명주기·동시성 | PASS | logging과 assertion만 바뀌어 production lifecycle/concurrency semantics를 변경하지 않았고 affected module test가 통과했다. |
| 4. 보안·비밀 | PASS | HTML/JavaScript의 금지 문자열과 secret redaction 검증을 유지했으며 credential을 추가하지 않았다. |
| 5. 성능·자원 | PASS | logging은 lazy lambda를 사용하고 dependency/classpath/fixture를 늘리지 않았다. |
| 6. API·유지보수 | PASS | Bluetape assertions와 기존 Kotlin/framework DSL을 재사용하고 개별 BOM이나 새 abstraction을 추가하지 않았다. |
| 7. 운영·검증 | PASS | RED 정적 baseline 이후 raw assertion/logger scan 0건, affected tests·compile·root detekt·diff check를 완료했다. |

## Fresh verification receipt

```text
BASE/HEAD: develop `b5a09c4b`, exact head `2652884d`.
STATIC RED→GREEN: legacy JUnit/raw assertion 168→0, Kinesis
LoggerFactory/LOGGER 6→0; setup precondition `requireNotNull` 8건은 유지.
COMPILE: affected module `compileTestKotlin` selectors 모두 PASS.
TEST: usage integration 3+4, promotion 17, pre-generated 9, planning 34 및
기타 대상 test가 PASS; root detekt 0 violation.
DIFF: `git diff --check` PASS.
```

이번 lane에서는 #785 production logging anchor와 #786~#790 test assertions를
같은 승인된 allowlist 안에서 함께 처리했으며, raw assertion fallback을 남기지
않았다. `requireNotNull` 8건은 fixture/property precondition이므로 assertion
migration 대상이 아니다.

## DoD Status

- [x] #785 Kinesis lazy logging 재사용
- [x] #786~#790 Bluetape assertion migration
- [x] #791 기존 ancestor/test contract 보존
- [x] 7-Tier review artifact 및 fresh verification receipt
- [ ] PR metadata·hosted CI·review/thread read-back, final fresh approval,
  rebase merge, canonical sync — coordinator closeout

현재 상태: **A2 FOLLOW-UP IMPLEMENTATION VERIFIED / COORDINATOR CLOSEOUT PENDING**.
