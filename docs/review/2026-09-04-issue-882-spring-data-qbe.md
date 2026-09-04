# Issue #882 Spring Data Exposed QBE/FluentQuery 구현 리뷰

## 범위

- `spring-data/r2dbc-webflux-exposed`: QBE repository factory, matcher, FluentQuery
  projection/page/count/exists, annotation·functional HTTP 경계
- 기존 explicit `UserExposedRepository` CRUD와 Spring Boot 4.1 auto-configuration
  충돌 방지
- `2.0.0` BOM, 양국 README, workflow/stale/coverage/lesson, stacked scope receipt

## 확인 결과

| 검토 항목 | 결과 | 근거 |
|---|---|---|
| 기존 CRUD 보존 | 통과 | `UserExposedRepository`와 service의 기존 transaction 경계를 변경하지 않고 별도 QBE interface 추가 |
| QBE matcher | 통과 | `loginPrefix` STARTING, `email` exact matcher와 immutable example snapshot |
| FluentQuery projection | 통과 | `name`, `login`만 선택하고 `UserSummary`로 변환; email/avatar는 응답에 없음 |
| paging metadata | 통과 | `PageRequest`, `hasNext`, 별도 `count`/`exists` terminal을 HTTP 응답에 포함 |
| HTTP 경계 | 통과 | `/api/users/qbe`, `/users/qbe`와 page/size 400 검증을 annotation·functional 테스트로 확인 |
| Flow cold/cancellation | 통과 | cold Flow를 두 번 collect하고 `take(1)` 취소 뒤 후속 `exists` 성공; PostgreSQL transaction context 유지 |
| 테스트 DB 격리 | 통과 | 다이얼렉트 계약 테스트가 남긴 전역 기본 DB를 Spring 통합 테스트 `@BeforeEach`에서 주입된 PostgreSQL DB로 복원 |
| auto-config 충돌 | 통과 | Boot 4.1 `DataR2dbcRepositoriesAutoConfiguration` 제외 후 Exposed factory 단일 등록 |
| consumer dependency | 통과 | versionless `exposed-spring-boot-r2dbc` alias와 root `bluetape4k-dependencies:2.0.0`만 사용 |
| 문서·가드 | 통과 | 변경 README 개별 parity/language, stale/actionlint/JSON/diff와 full module 검증 완료 |

## 리스크와 후속 조치

- upstream R2DBC QBE `findAll` executor가 PostgreSQL에서 transaction context를
  emission에 추가하므로 일반 collector와 context invariant가 충돌할 수 있다. 예제는
  transaction-scoped collection으로 resource 반환을 검증했으며, 라이브러리 수정 시
  workaround를 제거할지 재평가한다.
- upstream QBE compiler의 ignore-case는 현재 지원하지 않아 예제 matcher에서 제외했다.
  필요하면 library capability가 추가된 뒤 별도 issue로 확장한다.
- `UserSummaryProjection`은 학습용 최소 projection이다. 운영 API에서는 권한별 field
  contract와 projection property 검증을 추가한다.
- 전체 README parity 스크립트의 기존 optimization 3건 실패는 이번 변경 파일과 무관하므로
  root closeout evidence에 baseline gap으로 기록한다. 변경한 README 쌍은 개별 검증한다.

## 리뷰 결론

기존 explicit CRUD를 유지하면서 Spring Data Exposed 2.0.0의 QBE와 FluentQuery를
실제 WebFlux consumer에 연결한 Type B 변경이다. targeted/full test와 문서·workflow
검증이 모두 fresh하게 통과하고 다섯 PR의 exact-head CI/review가 확인될 때까지 병합하지
않는다.
