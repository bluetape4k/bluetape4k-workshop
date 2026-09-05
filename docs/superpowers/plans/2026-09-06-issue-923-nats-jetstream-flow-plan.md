# Issue #923 NATS JetStream Consumer Flow 구현 계획

## 목표

신규 messaging 예제에서 stable 2.0.0 NATS Flow API의 pull/push 소비, 수동 ack 결정,
bounded backpressure와 cancellation cleanup을 실제 JetStream 회귀로 고정한다.

## 순서

1. GNO/live Issue와 upstream Issue #1350/PR #1476, dependencies 2.0.0 provider source를 대조한다.
2. 설계·계획을 architecture/API/performance/security/test/operator 관점에서 독립 검토해 P0/P1을 제거한다.
3. 신규 module build와 Testcontainers fixture, cold/pull/push/manual-ack/drop/cancellation 회귀를 먼저 추가해
   production facade 부재 또는 계약 미충족 RED를 확인한다. Pull cold 단위 회귀는 선행 생성된
   `ConsumerContext`의 `iterate()`가 collect 전 호출되지 않음을 검증하고, push는 subscription 미생성을 검증한다.
   cancel 뒤 새 handle과 이전 handle cleanup도 단위 회귀로 고정한다. 모든 live collection에는 per-test timeout과
   유한 `take`/`first`를 사용하고 background collector는 `finally`에서 `cancelAndJoin()`한다. Connection은 `use`로
   닫는다. `term()` negative test와 drop collector에도 bounded timeout stop 조건을 둔다. Drop burst는 첫 delivery
   barrier가 열린 뒤 시작하며 timeout은 재현 실패로 처리한다. Ack는 `ConsumerInfo.numAckPending == 0` bounded
   polling, nak/term은 delivery count와 consumer state로 검증한다.
4. `JetStreamFlowWorkshop`과 최소 helper를 구현한다. 공개 provider Flow를 감싸거나 복제하지 않고 stream,
   consumer와 bounded options 조립만 담당한다.
5. module/root README pair, coverage matrix, ecosystem reuse manifest, lesson을 갱신한다. `Examples.yml`의 push와
   pull_request path, container task, report artifact를 추가하고 `scripts/smoke-validate.sh messaging` task,
   required-module 목록과 issue-specific stale guard를 추가한다. Manifest에는 issue 923, exact head/base,
   allowed paths와 implementation review artifact를 고정한다.
6. clean module test, root detekt, messaging/full/stale/ecosystem/README/assertion/actionlint/diff 검증과
   dependency insight를 실행한다.
7. 독립 구현 리뷰 P0/P1 0건 후 Lore commit, push, Korean PR을 만들고 exact-head CI를 확인한다.
8. Issue #940 branch를 #923 head 위에 쌓으며 최종 승인 전에는 merge하지 않는다.

## 중단 조건

- stable 2.0.0 artifact가 `consumeAsFlow`를 제공하지 않으면 snapshot으로 우회하지 않고 dependency evidence를 기록한다.
- 실제 JetStream에서 ack/redelivery/drop/cancellation 계약을 재현하지 못하면 완료로 표시하지 않는다.
- user의 root dirty change와 unrelated worktree를 건드리지 않는다.

## 체크리스트

- [x] GNO/live/upstream/current-code 조사
- [x] baseline과 RED 증거
- [x] 설계와 계획 작성
- [x] 독립 설계·계획 리뷰 P0/P1 0건
- [x] production 구현
- [x] 문서·manifest·workflow guard
- [x] clean tests와 정적/계약 검증
- [x] 독립 구현 리뷰 P0/P1 0건
- [ ] PR exact-head hosted CI와 metadata 확인
