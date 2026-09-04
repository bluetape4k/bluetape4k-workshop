# Issue #881 Exposed JDBC/R2DBC cursor pagination 구현 리뷰

## 범위

- `exposed/mvc-jdbc`: `BookRepository.findCursorPage`, MVC cursor endpoint, sparse ID
  mutation 경계
- `exposed/webflux-r2dbc`: `LongR2dbcRepository` 변환, suspend cursor endpoint, 취소 및
  pool resource release
- dependency `2.0.0` BOM, 양국 README, workflow/stale/coverage/lesson 등록

## 확인 결과

| 검토 항목 | 결과 | 근거 |
|---|---|---|
| JDBC keyset 경계 | 통과 | primary-key predicate와 `pageSize + 1` sentinel을 사용하는 upstream extension 위임 |
| R2DBC keyset 경계 | 통과 | typed `LongR2dbcRepository` adapter와 `suspendTransaction` service 경계 |
| sparse ID·insert/delete | 통과 | 두 repository contract test에서 cursor 이후 `[5, 7]` 연속성 확인 |
| page-size guard | 통과 | endpoint 및 R2DBC contract에서 `0`/`10001`을 400 또는 `IllegalArgumentException`으로 거부 |
| R2DBC cancellation | 통과 | size-one pool에서 mapper 취소 후 쓰기 rollback 및 후속 조회 성공 |
| 기존 API 회귀 | 통과 | `:exposed-mvc-jdbc:test` 17개, `:exposed-webflux-r2dbc:test` 20개 전체 재실행 통과 |
| token 보안 경계 | 통과 | raw ID는 workshop 전용으로 명시하고 인코딩·서명·만료·scope를 caller-owned로 문서화 |
| consumer dependency | 통과 | root `bluetape4k-dependencies:2.0.0`, versionless module aliases만 사용 |

## 리스크와 후속 조치

- 예제 endpoint는 raw `Long`을 반환하므로 production 복사 시 반드시 opaque signed
  token으로 교체한다.
- 단일 primary key cursor만 다루며 composite sort/search cursor와 암호화는 범위 밖이다.
- R2DBC `LongIdTable` 승격은 기존 `BookTable.id` 매핑을 typed `EntityID<Long>`으로
  바꾸므로 새 repository는 `.value` 변환을 유지해야 한다.
- PostgreSQL Testcontainers 전체 실행과 hosted CI exact-head는 batch closeout에서
  다시 확인하고, 통과 전에는 어떤 PR도 병합하지 않는다.
- 전체 README parity 스크립트는 기존 optimization 모듈 3건의 언어 스위치 누락으로
  실패하지만, 변경한 root·MVC·R2DBC README parity는 개별 검증에서 통과했다.

## 리뷰 결론

기존 offset endpoint를 보존하면서 Exposed 2.0.0의 bounded keyset cursor를 JDBC와
R2DBC consumer에 같은 wire contract로 추가한 Type B 변경이다. 로컬 targeted/full
검증과 문서·stale·workflow 검증이 통과하면 batch의 다음 이슈로 진행할 수 있으며,
최종 병합은 다섯 PR의 exact-head CI/review가 모두 확인된 뒤 별도 승인으로 제한한다.
