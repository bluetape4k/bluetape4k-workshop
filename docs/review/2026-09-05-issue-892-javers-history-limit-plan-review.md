# Issue #892 설계·계획 리뷰

## 범위

- JaVers `QueryBuilder.limit`와 `AggregateRepository.loadHistory` 2.0.0 계약
- `exposed/javers-persistence-audit`의 Redis read/decode 경계
- `exposed/javers-approval-workflow`의 ordering과 승인 이력 의미
- JVM overload, validation, unknown-id, raw snapshot 노출 경계

## 발견과 반영

초기 설계는 `QueryBuilder.limit`만으로 Redis materialization이 제한된다고 가정했다. 독립
성능·안정성 리뷰에서 `RedissonCdoSnapshotRepository.getStateHistory`가 `getAll()` 이후
limit을 적용한다는 P1을 확인했다. Exact-instance query 전용 range adapter, 명시적 QueryParams
allowlist, unsupported query fallback, counting codec 검증으로 보정했다.

보안·운영 리뷰는 oldest-first에서 newest-first로의 behavioral migration, unknown history와
존재 여부의 구분, raw `CdoSnapshot` authorization/redaction 책임, 실제 JVM overload 호출을
요구했다. 설계·계획과 문서·테스트 조건에 모두 반영했다.

## Gate 결과

- 성능·안정성: P0=0, P1=0
- 보안·운영: P0=0, P1=0
- 결론: 구현 gate 진행 가능
