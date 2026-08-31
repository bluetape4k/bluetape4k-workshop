# #866 spec/plan 7-Tier 검토

## 범위와 근거

- 대상 spec: `docs/superpowers/specs/2026-08-30-leader-backend-diagnostics-design.md`
- 대상 plan: `docs/superpowers/plans/2026-08-30-issue-866-leader-backend-diagnostics-plan.md`
- 대상 코드: `leader/backend-comparison-lab`
- upstream 근거: sibling `bluetape4k-leader/leader-core`,
  `leader-micrometer`, `leader-spring-boot`의 현재 `develop` source와 Maven
  metadata (`bluetape4k-dependencies:2.0.0-SNAPSHOT`,
  `leader-spring-boot:1.0.0-SNAPSHOT`)
- 검토일: 2026-08-30

## 여섯 관점 결과

| 우선순위 | 관점 | 근거와 검토 결과 | 조치 | 재검토 |
|---|---|---|---|---|
| N/A | Performance | probe는 호출 thread에서 한 번 실행하고 client/thread/retry/buffer를 만들지 않는다. metric은 active probe 결과당 counter 한 개만 만든다. benchmark 대상 hot path가 아니다. | N/A를 plan 8에 기록 | Step 4-P |
| N/A | Stability | `LeaderBackendDiagnosticsProbe`의 cancellation/interrupt 재전달과 local delegate lifecycle을 명시했다. context test가 startup fallback과 metric 중복을 검증한다. | N/A를 plan 3~5와 위험표에 기록 | Step 4-P/6-R |
| N/A | Security | descriptor와 health detail의 허용 필드, raw exception/credential/endpoint 비노출, 기본 passive mode를 spec과 test에 고정했다. 외부 입력은 catalog id와 enum binding으로 제한한다. | N/A를 plan 3~6에 기록 | Step 6-R |
| N/A | Operator/Ops | Actuator endpoint, health enablement, timeout, bounded reason, rollback, smoke/CI 등록과 release version pin 제외를 모두 명시했다. | N/A를 plan 6~9에 기록 | Step 6-R |
| N/A | Developer/API | root BOM versionless alias, 기존 profile id/public scenario 보존, upstream adapter 직접 사용, `ApplicationContextRunner`와 Kotlin assertion 규칙을 명시했다. | N/A를 plan 2~5에 기록 | Step 5/6-R |
| N/A | User/Caller | 두 README에 passive/active 호출, 상태표, metric tag, unsupported 경계, 실제 practice module 이동 경로를 동기화하도록 했다. | N/A를 plan 6에 기록 | Step 6-R |

## 통합 검토

| 확인 항목 | 결과 |
|---|---|
| spec 요구사항 → plan task | PASS: 의존성, provider, Spring context, 문서, CI, verifier/review/lesson이 순서대로 매핑됨 |
| task ordering | PASS: red 테스트가 production 구현보다 먼저이고, docs/CI는 source green 뒤에 실행됨 |
| failure/edge/lifecycle | PASS: unsupported, exception, cancellation, invalid timeout, local fallback, metric duplication을 모두 명시함 |
| Spring Boot 조건/순서 | PASS: `leader-spring-boot` auto-config를 사용하고 custom blocking elector가 local fallback을 대체하는 context proof를 요구함 |
| dependency/BOM | PASS: root `bluetape4k-dependencies` 단일 authority와 versionless alias를 exact file task로 고정함 |
| README/locale/CI | PASS: `README.md`, `README.ko.md`, matrix, workflow, stale-check을 모두 task로 지정함 |
| diagram/CHANGELOG/release | N/A: 기존 diagram의 구조/텍스트를 변경하지 않으며 release/manual version pin과 CHANGELOG는 범위 밖이다. lesson에 근거를 남긴다. |
| rollback/rerun | PASS: artifact drift, bean selection, metric duplication, cancellation, registration, credential leak별 rerun 지점을 지정함 |

## 결론

최신 spec/plan은 P0=0, P1=0이다. 구현을 시작할 수 있다. P2/P3로 분류할
추가 finding도 없으며, performance/stability는 동기 stateless probe라 별도
stress harness가 N/A라는 근거를 plan에 남겼다. 구현 중 upstream API 또는
Spring condition이 달라지면 spec/plan을 먼저 갱신하고 이 review를 다시 실행한다.

## Writer DoD

- SPW-01: PASS — artifact kind, 독자, 이슈/모듈, upstream source, 정확한 API와 미확정 경계를 기록했다.
- SPW-02: PASS — spec은 목표/제외/구조/실패/보안/테스트/호환성/DoD를, plan은 순서/파일/명령/rollback을 포함한다.
- SPW-03: PASS — 한국어 기술 문체, 고정 API token, 상태/원인 용어를 유지했고 terminology audit를 통과했다.
- SPW-04: PASS — upstream source와 issue body를 대조해 상태 매핑과 descriptor/metric 계약을 추적했다.
- SPW-05: PASS — Markdown read-back, 표/코드 fence/링크 구조를 확인했고 `git diff --check`가 통과했다.

검증 명령:

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/specs/2026-08-30-leader-backend-diagnostics-design.md \
  docs/superpowers/plans/2026-08-30-issue-866-leader-backend-diagnostics-plan.md \
  docs/review/2026-08-30-issue-866-spec-plan-review.md
```

결과: `git diff --check` PASS, terminology audit 2 files PASS.
