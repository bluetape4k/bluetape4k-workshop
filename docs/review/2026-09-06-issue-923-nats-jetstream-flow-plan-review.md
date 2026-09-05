# Issue #923 NATS JetStream Consumer Flow 설계·계획 리뷰

## 범위

- architecture와 public API
- performance와 runtime stability
- security와 privacy
- test와 verification
- operator와 workflow
- Issue #923, dependencies 2.0.0 provider source, 현재 messaging module 구조

## 발견과 반영

- Pull cold 의미를 durable `ConsumerContext` 생성과 collect-time `iterate()`/`IterableConsumer` 생성으로 분리했다.
- `term()` 비재전달과 drop 미재현이 timeout을 성공으로 오판하거나 hang하지 않도록 bounded negative-test와
  background collector `finally { cancelAndJoin() }`를 명시했다.
- 모든 live collection에 per-test timeout, 유한 `take`/`first`, connection `use`를 적용하고 ack/nak/term은
  delivery count와 `ConsumerInfo`를 함께 관찰하도록 보강했다.
- Drop publish burst는 첫 delivery barrier 뒤 시작하며 재현 timeout은 명시적 실패로 처리한다.
- Stable `NatsServer.Launcher` JetStream fixture, test별 고유 이름, 직렬 실행을 고정했다.
- Examples push/PR path, container task, artifact와 smoke messaging/required/stale, manifest exact membership을
  개별 완료 조건으로 명시했다.

## Gate 결과

- architecture/API: 초기 P1=2, cold 의미와 bounded negative-test 반영 후 P0=0/P1=0 PASS
- performance/stability: 초기 P1=2, timeout/cancel/drop barrier 반영 후 P0=0/P1=0 PASS
- security/privacy: P0=0/P1=0 PASS
- test/verification: 초기 P1=5, live lifecycle/state 관측과 fixture 계약 반영 후 P0=0/P1=0 PASS
- operator/workflow: 초기 P1=2, exact CI/smoke/manifest membership 반영 후 P0=0/P1=0 PASS
- 결론: 전체 P0=0/P1=0. RED와 구현 gate 진행 가능
