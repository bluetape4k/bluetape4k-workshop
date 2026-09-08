# Issues #988~#993 구현 계획 6관점 검토

## 검토 범위

- 대상: `docs/superpowers/plans/2026-09-08-issues-988-993-ecosystem-reuse-plan.md`
- 요구사항 매핑: #989 Tink alias와 usage-billing, #990 codec, #991 correlationRef, #992 reservation threat model, #988 Jackson3, #993 coroutine lifecycle

## 발견 사항과 반영

| 우선순위 | 관점 | 발견 사항 | 계획 수정 |
| --- | --- | --- | --- |
| P1 | stability/Ops | `dependencyInsight`가 exact project/module/configuration을 고정하지 않음 | production `runtimeClasspath`와 gate용 `testRuntimeClasspath`, bounded flags를 명시 |
| P1 | stability | #988 PR CI module coverage 누락 | `Examples.yml` 또는 동등 required check와 smoke/nightly/validation 경계 갱신 추가 |
| P1 | operator/Ops | manifest/inventory/lesson 작업이 없음 | Task 0을 추가해 이슈별 scope, inventory, review, lesson, checker 실행을 선행 |
| P1 | developer/API | raw `cancel()`이 provider close 상태를 우회할 수 있음 | `DefaultCoroutineScope` 소유와 `scope.close()`, 반복·동시 close 상태 검증 추가 |
| P1 | user/caller | dependency 비용을 production 이전 뒤에 측정함 | baseline → dependency-only delta → 수용 결정 → production migration 순으로 변경 |
| P1 | user/caller | 생성과 검증 경로를 모두 `digestHex`로 처리함 | generator=`digestHex`, verifier=`matchesHex` callsite 구분과 malformed/uppercase/length 회귀 추가 |
| P2 | stability | baseline mismatch 중단과 bounded execution이 없음 | mismatch 즉시 중단, raw 복원, timeout과 serial Gradle flags 추가 |
| P2 | security | reservation DB/log 경계 assertion이 없음 | HMAC `keyDigest`, unkeyed `requestFingerprint`, 원문 비노출 assertion 분리 |
| P2 | performance | 동시 close와 footprint evidence가 없음 | #993 concurrent close, Tink jar와 bootJar delta 추가. 성능 benchmark는 무주장 근거로 N/A |
| P2 | user/caller | README와 mixed-version evidence가 없음 | affected service/composition 한·영 README와 양방향 호환 test 추가 |

## 요구사항 추적

| 이슈 | 코드 계약 | 핵심 검증 | 운영 산출물 |
| --- | --- | --- | --- |
| #989 | versionless alias, `digestHex`/`matchesHex` | 10 production callsite, independent oracle, old/new 호환, footprint | manifest/inventory/review/lesson/PR |
| #990 | codec framing 보존, digest primitive만 교체 | golden bytes, strict malformed/duplicate/trailing/size | stacked base와 exact-head receipt |
| #991 | lowercase 16자 correlationRef | email/Unicode golden, PII 비노출, collision 용도 | stacked base와 exact-head receipt |
| #992 | request fingerprint만 unkeyed Tink digest | HMAC 경계, DB/log 비노출, golden framing | threat model과 stacked receipt |
| #988 | Ktor singleton, Spring isolated factory | JSON baseline 표, 세 module required CI | matrix/smoke/CI receipt |
| #993 | component-owned `DefaultCoroutineScope` | cancellation, 반복·동시 close, scope-last | lifecycle review와 exact-head receipt |

## 최종 수렴

독립 재검토에서 performance, stability, security, operator/Ops, developer/API, user/caller 모두 P0=0, P1=0으로 수렴했다. 구현은 #989→#990→#991→#992→#988→#993 순서로만 진행하고 각 PR gate가 끝나기 전 다음 이슈 코드를 수정하지 않는다.
