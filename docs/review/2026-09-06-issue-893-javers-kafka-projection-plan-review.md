# Issue #893 JaVers Kafka projection 설계·계획 리뷰

## 범위

- architecture와 public API
- performance와 runtime stability
- security와 privacy
- test와 verification
- operator와 workflow
- Issue #893, dependencies 2.0.0이 관리하는 JaVers 1.0.0 provider source, 현재 module

## 초기 발견과 반영

- Redisson projection은 snapshot과 head metadata가 partial 상태로 남을 수 있으므로 projector target에서
  제외하고 transactional Lettuce repository로 범위를 좁혔다. 기존 bounded Redisson read 예제는 유지한다.
- Redis `MULTI/EXEC`도 rollback을 제공하지 않으므로 atomic/all-or-nothing 표현을 제거했다.
  `EXEC` 이전 failure와 offset 미커밋만 회귀로 고정하고, 실행 이후 command error/connection loss는
  commit-unknown 또는 partial 상태가 될 수 있음을 명시했다.
- write-only Kafka repository의 read API 오용을 막기 위해 내부 writer/reader를 감춘 command/query facade로
  계약을 변경했다.
- `group.id`, auto-commit false, earliest reset을 생성 전 검증하고 first-empty-poll 이후 record 처리,
  실패 instance close 후 같은 group의 새 consumer가 committed offset부터 수행하는 retry와 successful
  commit을 검증하도록 했다. 동일 consumer position의 자동 rewind는 가정하지 않는다.
- config-owned consumer/producer와 repository connection은 pipeline이 닫되 caller-owned RedisClient는 닫지
  않는다. 모든 close를 시도하고 최초 failure에 나머지를 suppressed로 보존한다.
- `replayUntilIdle`은 기본 연속 idle poll 3회와 외부 deadline을 사용하는 finite catch-up 전용으로 제한했다.
- duplicate lookup의 history-length 비용, payload authorization/redaction, TLS/SASL/ACL, unsalted fingerprint의
  correlation 성격을 운영 한계로 명시했다.
- 기존 smoke/full membership은 구조적으로 검증하고, README/lesson/stale guard에서 Issue #290 후속을 추적한다.

## Gate 결과

- architecture/API: P0=0, P1=0, PASS
- performance/stability: P0=0, P1=0, PASS
- security/privacy: P0=0, P1=0, PASS
- test/verification: P0=0, P1=0, PASS
- operator/workflow: P0=0, P1=0, PASS
- 결론: 전체 P0=0, P1=0. RED 구현 gate 진행 가능
