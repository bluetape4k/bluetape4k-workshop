# Issues #988~#993 설계 6관점 검토

## 검토 범위

- 대상: `docs/superpowers/specs/2026-09-08-issues-988-993-ecosystem-reuse-design.md`
- 기준: `develop` `c7087f48cd563fdd1282fdfb7547e9780cc88d33`
- 방식: performance, stability, security, operator/Ops, developer/API, user/caller 독립 read-only 검토 후 main session 통합

## 발견 사항과 반영

| 우선순위 | 관점 | 발견 사항 | 반영 |
| --- | --- | --- | --- |
| P1 | stability | Jackson provider 기본값이 Spring 입력 경계를 완화할 수 있음 | unknown/trailing/duplicate/malformed/null/coercion baseline이 모두 같을 때만 교체하는 fail-closed 조건 추가 |
| P1 | stability | #988 세 module이 required CI에서 실행되지 않을 수 있음 | PR CI 또는 동등 required check에서 세 test task 실행을 필수화하고 local receipt 대체 금지 |
| P1 | operator/Ops | 변경 경로의 active manifest scope와 lesson이 없음 | 이슈별 manifest/inventory/review/lesson/exact-head receipt를 필수 산출물로 추가 |
| P1 | user/caller | production 비용 판정에 test graph만 사용하고 중단 기준이 없음 | `runtimeClasspath`, `bootJar` delta와 5 MiB/중복 crypto stop 기준 추가 |
| P2 | performance | hot path와 전이 dependency 비용 검증이 불명확함 | 동일 JDK primitive라 성능 향상 주장을 금지하고 runtime graph, jar/bootJar 비용을 측정. 별도 benchmark는 N/A |
| P2 | security | HMAC `keyDigest`와 unkeyed `requestFingerprint`가 혼동될 수 있음 | service/repository 저장 필드와 변경 금지 대상을 명시하고 DB/log 원문 비노출 검증 추가 |
| P2 | developer/API | provider lifecycle 종료 API가 불명확함 | `DefaultCoroutineScope`, `scope.close()`, 상속, `isActive`, `destroyMethod` 계약 명시 |
| P2 | user/caller | SHA-256이 인증된 integrity로 오해될 수 있음 | 비인증 checksum/계약 일치로 한정하고 hostile tampering에는 MAC/signature가 필요함을 명시 |
| P2 | user/caller | mixed-version과 README 예제가 없음 | 양방향 old/new 호환, 무마이그레이션, rollback, 한·영 README 예제와 경고 추가 |

## 최종 수렴

| 관점 | P0 | P1 | 결론 |
| --- | ---: | ---: | --- |
| Performance | 0 | 0 | P2는 비용 측정과 benchmark N/A 근거로 처리 |
| Stability | 0 | 0 | 재검토 PASS |
| Security | 0 | 0 | 재검토 PASS |
| Operator/Ops | 0 | 0 | 재검토 PASS |
| Developer/API | 0 | 0 | 재검토 PASS |
| User/caller | 0 | 0 | 재검토 CLEAR |

## Gate

최신 설계의 P0=0, P1=0이다. 실제 manifest, CI, dependency footprint, lifecycle test와 exact-head receipt는 구현 검증에서 확인한다.
