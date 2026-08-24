# Issue #527 Last-Mile Routing 설계 리뷰

- 검토일: 2026-08-24
- 대상: `docs/superpowers/specs/2026-08-24-issue-527-last-mile-routing-design.md`
- 기준: live Issue #527, Epic #523, 선행 #524, GNO 조사, 현재 `develop`의 #525 구현
- 판정: P0/P1 없음, 설계 리뷰 PASS

## 검토 범위와 증거

설계 문서가 고정 matrix 우선이라는 Issue 범위를 유지하는지, PostgreSQL 권위
상태·provider 결과의 버전 경계·callback/idempotency·browser redaction을 한
모델로 통합하는지 검토했다. `optimization/field-service-dispatch`의 실제
domain/planner/persistence/browser 경계와 `optimization/planning-contracts`의
현재 internal 구현을 read-back했다. Timefold 공식 문서는 비동기 결과 수신,
애플리케이션 backend 권위, hard constraint와 재계획 개념을 확인하는 보조
근거로만 사용했다.

## 독립 관점 결과

| 관점 | 결과 | 핵심 판단과 처분 |
|---|---|---|
| Performance | P0=0, P1=0, P2=2 | matrix O(1) lookup, 입력 상한, bounded page/queue, deterministic tie-break를 설계에 고정했다. 실제 benchmark 숫자와 SQL `EXPLAIN`은 구현 계획의 검증 Task로 이연한다. |
| Stability | P0=0, P1=0, P2=1 | provider outage, duplicate/out-of-order callback, reconnect, burst coalescing, virtual-thread shutdown을 명시했다. 실제 restart/Testcontainers 증거는 구현 단계 전에는 없다. |
| Security | P0=0, P1=0, P2=1 | raw payload/address/token 금지, redacted read model, CSP/DOM API/textContent를 명시했다. 운영 인증·CSRF는 이번 synthetic demo 비목표로 남긴다. |
| Operator/Ops | P0=0, P1=0, P2=2 | health/readiness, enum 기반 오류, bounded retry/dead-letter, outbox와 audit 경계를 명시했다. production migration/runbook은 범위 밖이다. |
| Developer/API | P0=0, P1=0, P2=1 | 독립 모듈과 작은 provider port, versioned request/result, HTTP 오류 enum을 고정했다. #524 구현 의존을 거부해 공개 API 결합을 막았다. |
| User/caller | P0=0, P1=0, P2=1 | pickup/delivery 순서·capacity·window·skill·started pin과 오류 상태를 browser projection에서 확인한다. 실제 지도 tile/GPS는 비목표다. |

## Main integration

| 우선순위 | 통합 항목 | 판정 | 구현 시 확인할 증거 |
|---|---|---|---|
| P0 | PostgreSQL 권위와 provider 경계 | PASS | proposal/committed 분리, carrier/job/plan/matrix version 재검증 |
| P0 | hard constraint와 started pin | PASS | planner fixture, stale approval CAS, started stop immutability |
| P0 | callback 안전성 | PASS | `(provider,eventId)` unique, digest conflict, stale revision audit |
| P1 | HTTP/browser 공개면 | PASS | ETag, redaction, CSP, safe DOM, 명시적 오류 enum |
| P1 | 운영·복구 | PASS | bounded executor, outbox replay, reconnect, readiness/metrics |
| P1 | 모듈·ecosystem 경계 | PASS | BOM-only, no `project(":optimization-planning-contracts")`, auto-registration |
| P2 | 실제 provider/인증/지도 | 보류 | 후속 provider adapter와 별도 production 보안 범위에서 재검토 |

## 결정과 다음 실행 경계

- 권장 선택 A를 채택한다. 고정 matrix와 deterministic provider가 첫 실행 경로다.
- #524의 구현 타입을 직접 참조하지 않는다. 공통 의미가 필요하면 이 모듈의
  normalized port와 wire envelope로 표현한다.
- provider outage와 matrix miss는 명시적 실패다. silent fallback을 추가하지 않는다.
- 이 리뷰는 구현·PR·merge 승인이 아니다. 다음 단계는 아래 계획을 작성하고
  계획 review를 PASS시키는 것이다.

## 문서 작성 게이트

| 항목 | 상태 | 증거 |
|---|---|---|
| SPW-01 독자·목적·출처·범위 | PASS | 설계·Issue·Epic·GNO·공식 문서와 검토 경계 |
| SPW-02 선택지·경계·실패·수용 기준 | PASS | six-lane 표, main integration, P0/P1 종료 조건 |
| SPW-03 한국어 기술 문체·용어 | PASS | 한국어 판단문과 보존된 Kotlin/HTTP/URL 토큰 |
| SPW-04 현재 소스·외부 계약 대조 | PASS | #524/#525 read-back 및 Timefold 공식 문서 대조 |
| SPW-05 read-back·Markdown·공백 | PASS | 문서 read-back, `git diff --check`, terminology audit 수행 |

## 최종 상태

`DONE (설계 리뷰 단계)`: P0/P1=0, 구현은 시작하지 않았다. 계획 review와 사용자
설계/계획 승인 전에는 모듈 코드나 외부 GitHub 상태를 변경하지 않는다.
