# Issue #894 Redis head metadata fail-closed 설계·계획 리뷰

## 범위

- architecture와 public API
- performance와 runtime stability
- security와 privacy
- test와 verification
- operator와 workflow
- Issue #894, dependencies 2.0.0 provider source, 현재 module

## 발견과 반영

- `getHeadId()`는 최초 load 뒤 cached head를 반환하므로 existing instance의 query-time corruption 감지를 제거했다.
  query마다 새 repository로 O(N) sequence scan하는 우회도 bounded-read 목적과 충돌하므로 채택하지 않았다.
- malformed metadata 검증은 factory/rebuild startup 경계로 제한하고 Kafka producer/consumer 생성 전에 수행한다.
- head 없음만으로 initial state를 판단하면 snapshot-only partial loss를 silent rewind할 수 있다. 최초 제안한
  public JaVers class query도 limit 전에 모든 snapshot을 materialize해 OOM 위험이 있으므로 제거했다. head가
  `null`일 때 documented Redisson/Lettuce snapshot-index key의 O(1) existence probe를 수행하고 snapshot이 남아
  있으면 generic integrity error로 거부한다.
- 실행 중 외부 변조 감지와 O(1) fresh validation API는 별도 upstream 설계 대상으로 분리했다.

## Gate 결과

- architecture/API: 초기 P1=3, O(1) probe 반영 후 P0=0/P1=0 PASS
- performance/stability: 초기 P1=2, O(1) probe 반영 후 P0=0/P1=0 PASS
- security/privacy: P0=0/P1=0 PASS
- test/verification: 초기 P1=2, failure precedence와 양 provider partial-loss 회귀 반영 후 PASS
- operator/workflow: 초기 P1=1, 파일·명령·predicate 명시 후 PASS
- 결론: 전체 P0=0/P1=0. RED와 구현 gate 진행 가능
