# Issue #881 Exposed JDBC/R2DBC cursor pagination

## Context

Exposed 2.0.0에 기본 키 keyset cursor pagination이 추가되었지만 workshop의
`exposed/mvc-jdbc`와 `exposed/webflux-r2dbc`는 기존 offset `findPage`/수동 Flow
조회만 보여주고 있었다. 두 consumer가 같은 pagination 계약을 실제 HTTP 예제로
보여주고, R2DBC 취소 시 connection이 반환되는 경계를 고정할 필요가 있었다.

## Decision or Finding

- JDBC `BookRepository`는 `findCursorPage`를 Exposed 2.0.0 extension에 위임하고,
  기존 `findPage` ABI는 제거하지 않는다.
- R2DBC `BookTable`을 `LongIdTable`로 승격하고 `BookRepository`를
  `LongR2dbcRepository` adapter로 만들어 suspend `findCursorPage`를 사용한다.
- 두 API는 `pageSize`, raw `Long cursor`, `SortOrder`를 받고 `content`, `nextCursor`,
  `hasNext`를 반환한다. page-size 상한과 `pageSize + 1` sentinel은 upstream 계약을
  그대로 사용한다.
- raw ID는 학습용 투명 token일 뿐이다. 운영 호출자는 동일한 정렬·필터·tenant 범위에
  묶어 인코딩·서명·만료·검증해야 한다.

## Outcome

두 예제에 `/api/v1/books/cursor`(MVC)와 `/api/books/cursor`(R2DBC)를 추가했다.
JDBC와 R2DBC 모두 bounded keyset 페이지와 잘못된 page-size 응답을 검증하며,
repository contract test는 sparse ID, cursor 앞 insert/delete 경계, R2DBC size-one
pool의 cancellation/resource release를 검증한다. R2DBC 기존 CRUD는
`LongR2dbcRepository`의 typed ID 매핑으로 유지한다.

## Verification

- `:exposed-mvc-jdbc:test --rerun-tasks`: 17 tests passed
- `:exposed-webflux-r2dbc:test --rerun-tasks`: 20 tests passed
- 두 모듈의 cursor contract 및 HTTP 경계를 포함한 전체 테스트를 재실행했다.
- 두 모듈 `build -x test`와 root `detekt`가 성공했다.
- root BOM은 `2.0.0`이며 새 source에 2.1 계열 참조가 없다.
- 변경 README 3쌍 parity와 전체 language 검증, stale-check, workflow actionlint, JSON,
  `git diff --check`가 통과했다. 전체 parity 스크립트의 기존 optimization 3건 실패는
  이번 변경 파일과 무관하다.

## Future Guidance

새 cursor consumer는 `ExposedCursorPage`의 `hasNext`/`nextCursor` 불변식을 그대로
전달하고, count query나 offset fallback을 cursor 경로에 섞지 않는다. cursor token은
API 경계에서만 opaque하게 만들며 정렬·조건·권한 scope가 바뀐 토큰을 재사용하지 않도록
검증한다. R2DBC는 반드시 `suspendTransaction` 안에서 Flow를 collect하고 취소 시
pool 반환을 회귀 테스트로 남긴다.
