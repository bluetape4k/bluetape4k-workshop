# Issue #923 NATS JetStream Consumer Flow 구현 리뷰

## 범위

- `JetStreamConsumerFlows` public consumer assembly
- cold pull/push handle lifecycle와 cancellation cleanup
- manual `ack`/`nak`/`term`, redelivery와 pending-drop failure
- Testcontainers NATS fixture와 bounded test termination
- dependencies 2.0.0, documentation, CI, smoke, manifest integration

## 구현 요약

- `NatsFlowLimits`가 Flow capacity, push pending message/byte limit과 receive timeout을 명시한다.
- Pull과 push는 stable provider의 `consumeAsFlow`를 직접 사용하고 adapter 코드를 복제하지 않는다.
- `AckDecision`은 caller의 처리 결과를 `ack`/`nak`/`term`에 명시적으로 대응시킨다.
- 실제 JetStream 회귀는 cold handle, 순서, ack drain, nak redelivery, term finalization과 pending drop을 검증한다.
- Drop burst는 첫 delivery barrier 뒤 core NATS publish를 사용하고 모든 background collector를 정리한다.

## 독립 리뷰

- architecture/API: PASS — stable provider의 공개 `consumeAsFlow`만 사용하며 ACK/NAK/TERM은
  collection 중 caller가 실행한다.
- performance/stability: PASS — 모든 live collection은 bounded timeout을 사용하고, pending-drop은
  고정 지연 없이 첫 delivery와 release barrier로 재현한다.
- security/privacy: PASS — payload나 credential을 log에 남기지 않고 NATS connection은 test scope에서 닫는다.
- test/verification: PASS — 최초 P1 4건을 cancellation/recollection, deterministic drop,
  분리된 NAK/TERM server-state 회귀로 해소했다.
- operator/workflow: PASS — Examples path/task/report, messaging smoke, stale guard와 manifest를 함께 등록했다.

## 검증

- TDD RED: production facade 부재로 compile 실패
- clean module: unit 5개와 Testcontainers integration 4개, 합계 9 tests PASS
- targeted real pending-drop Testcontainers regression: release barrier 버전 3회 반복 PASS
- cancellation: active pull/push를 `cancelAndJoin()`한 뒤 interrupt, `close()`/`unsubscribe()`와
  동일 Flow 재수집 PASS
- manual acknowledgement: collection 내부 ACK, NAK delivery count `1, 2`, TERM non-redelivery와
  `ConsumerInfo.numAckPending == 0` PASS
- root detekt, messaging smoke, stale-check, README language/parity, actionlint, manifest JSON,
  diff-check, ecosystem checker 113 tests, assertion governance 1,199 source scan PASS
- dependency insight: `bluetape4k-nats`는 root `bluetape4k-dependencies:2.0.0` BOM으로 resolve됨
- hosted exact-head CI: PR 생성 후 확인

## 발견 사항과 해소

- 최초 통합 테스트가 Flow 종료 뒤 ACK/NAK를 실행해 ack-wait redelivery와 혼동될 수 있었음 —
  `onEach`에서 즉시 승인하도록 옮기고 delivery metadata를 검증했다.
- 실제 cancellation cleanup 회귀가 없었음 — blocking receive interrupt와 handle cleanup,
  sequential recollection을 pull/push 각각 고정했다.
- pending-drop이 `delay(2.seconds)`에 의존했음 — `CompletableDeferred` release barrier로 교체했다.
- NAK와 TERM이 한 테스트에 결합되어 있었음 — 별도 테스트와 server state assertion으로 분리했다.

## Gate

- P0: 0건
- P1: 0건
- 결론: implementation gate PASS, hosted exact-head CI만 PENDING
