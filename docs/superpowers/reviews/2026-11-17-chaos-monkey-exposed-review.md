# Chaos Monkey 학생 CRUD Exposed 전환 최종 리뷰

## 리뷰 범위

- 대상 slice: `spring-boot/chaos-monkey`
- 기준: `develop` `6058ffa7c76e8426c73ee26cadcfe3c6dcde1973`
- 변경: Student CRUD persistence 경계, H2 bootstrap 식별자, 회귀 테스트,
  README 두 로케일, 설계·계획·lesson
- 제외: PostgreSQL 제어·migration SQL, Vert.x/R2DBC/Redisson 기술 시연,
  PR·merge·release

## 독립 관점 결과

| 관점 | 1차 결과 | 보완 및 최종 결과 |
|---|---|---|
| 성능 | P0 0, P1 0, 테스트 DB teardown P2 1 | `closeAndUnregister` 추가 후 P0 0, P1 0, P2 0. 향후 Spring 다중 호출 경계는 P3 watch |
| 안정성 | P0 0, P1 0, 테스트 manager 수명 P2 1 | teardown `finally`와 fresh module check로 P0 0, P1 0, P2 0 |
| 보안 | P0 0, P1 0 | Exposed bound parameter/검증·secret scan 재확인, P0 0, P1 0, P2 0, P3 0 |
| Operator/Ops | P0 0, P1 0, 예외·transaction 경계 P2 1 | 현재 caller가 단일 repository 호출이고 호환성 비목표를 설계에 명시해 P2를 범위 밖으로 처분 |
| Developer/API | P0 0, P1 0, KDoc P3 1 | 새 config/table 한국어 KDoc 추가 후 P0 0, P1 0, P2 0, P3 0 |
| User/Caller | P0 0, P1 0, README 문구 P3 1 | 한국어 README 경계 설명을 명확히 수정 후 P0 0, P1 0, P2 0, P3 0 |

## 통합 판정

- 최종 `P0 = 0`, `P1 = 0`, 활성 `P2 = 0`이다.
- 현재 `StudentService`와 controller에는 여러 repository 호출을 묶는
  `@Transactional` 경계나 `DataAccessException` catch가 없다. 따라서 각
  repository 메서드가 소유하는 Exposed transaction과 Exposed 예외 타입은
  이번 예제의 승인된 비목표로 문서화했다.
- 향후 caller가 Spring transaction으로 여러 repository 호출을 묶으면
  `SpringTransactionManager`, atomicity 회귀 테스트, 예외 매핑을 별도 설계한다.
- 새 public 예제 타입의 한국어 KDoc, README locale parity, H2 quoted
  identifier 설명, 실제 Gradle project path를 확인했다.

## 검증 증거

- RED: 구현 전 `StudentTable`/생성자 미해결 compile failure.
- GREEN: `StudentJdbcRepositoryTest` 2개 통과.
- `./gradlew :spring-boot-chaos-monkey:cleanTest :spring-boot-chaos-monkey:test --no-build-cache`: 4 passing.
- `./gradlew :spring-boot-chaos-monkey:compileKotlin :spring-boot-chaos-monkey:compileTestKotlin --rerun-tasks`: `BUILD SUCCESSFUL`.
- `./gradlew :spring-boot-chaos-monkey:check --rerun-tasks`: 4 passing, `BUILD SUCCESSFUL`.
- Exposed runtime dependency graph가 `bluetape4k-dependencies` BOM과
  `exposed-core`/`exposed-jdbc` alias로 해석됨.
- 한국어 용어 감사 2개 파일 findings 0, `git diff --check` 통과.
- `spring-boot/chaos-monkey` Kotlin 소스의 `JdbcTemplate`, `RowMapper`,
  Student raw SQL 검색 결과 없음.

## 잔여 위험

- root 전체 CI, root `detekt`, PR checks는 이번 요청에 포함되지 않아 실행하지
  않았다. 모듈에는 별도 `detekt` task가 없어 root detekt로 대체하지 않았다.
- branch는 `refactor/chaos-monkey-exposed` worktree에 유지하며 PR/merge/정식
  develop 동기화는 별도 승인 전까지 수행하지 않는다.
