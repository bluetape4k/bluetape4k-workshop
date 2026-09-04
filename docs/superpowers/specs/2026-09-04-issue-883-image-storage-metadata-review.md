# Issue #883 metadata capability 설계 통합 review

## 검토 기준

대상은 [설계 문서](2026-09-04-issue-883-image-storage-metadata-design.md)와 upstream
`ImageObjectMetadataReader` contract, 현재 `ImageDerivativeWorkflowService` 및 테스트다.
검토 시점에는 P0/P1 blocker가 없어야 구현을 진행한다.

| 관점 | 확인 내용 | 결과 |
| --- | --- | --- |
| 요구사항/범위 | optional capability, unsupported fallback, no body download, cleanup 경계가 수용 기준에 있음 | PASS, P0=0/P1=0 |
| Kotlin/API | `ImageStorage` ABI를 유지하고 `as?` capability 탐색만 사용함 | PASS, P0=0/P1=0 |
| 동시성/취소 | reader 예외와 `CancellationException`을 workflow catch/cleanup 경로로 전파함 | PASS, P0=0/P1=0 |
| 보안/데이터 | ETag을 hash로 해석하지 않고 metadata key/size 불일치를 성공으로 저장하지 않음 | PASS, P0=0/P1=0 |
| 테스트/운영 | capability/fallback/failure/metrics-wrapper와 no-download를 deterministic fixture로 검증함 | PASS, P0=0/P1=0 |
| 문서/지역화 | module/root README, matrix, workflow, stale-check, lesson의 양국/guard 경계를 포함함 | PASS, P0=0/P1=0 |
| 통합 판단 | public response schema와 dependency version을 불필요하게 넓히지 않음 | PASS, P0=0/P1=0 |

## 결정

권고안 A를 승인한다. metadata 기준 정보는 upload 직후 object별 1회만 읽고, size/content type은
조회 결과를 우선으로 사용한다. reader 오류·key/size mismatch는 fail-closed로 처리한다.
설계 문서의 수용 기준과 구현 계획 사이에 미해결 P0/P1은 없다.

## 남은 검증

실제 S3 HEAD 교체 경합과 Local secure attribute 구현은 upstream library가 소유하므로
workshop에서는 재구현하지 않는다. PR 전에는 module test, readme/guard 검사와 hosted CI가
해당 경계를 깨지 않는지만 확인한다.
