# Issue #530 Warehouse Allocation 구현 교훈

## 결정

planner와 PostgreSQL reservation authority를 독립 경계로 두었다. planner는
immutable snapshot에서 deterministic proposal과 reason만 만들고, 승인 transaction이
현재 stock revision과 available quantity를 CAS로 재확인한다. #524와는 request/callback
계약을 좁게 검증하며 내부 구현을 공유하지 않는다.

GNO는 collection을 고정하지 않은 전역 query로 탐색했다. 탐색 결과는 설계 선택을
돕는 조사 근거이고, 현재 GitHub Issue와 저장소 파일이 live authority다.

## 구현에서 확인한 점

- 모듈 등록은 `settings.gradle.kts` 자동 검색만으로 끝나지 않는다. optimization
  README 양쪽, Examples workflow test/artifact path, `smoke-validate.sh`, T3 validation
  matrix, stale required-file list를 함께 갱신해야 한다.
- consumer module은 root `bluetape4k-dependencies` BOM만 사용하고 개별 Bluetape BOM이나
  명시 버전을 추가하지 않는다.
- `X-Demo-Operator`는 local demo guard일 뿐 인증이 아니며, loopback binding과 redacted
  read model을 문서에서 함께 설명해야 한다.
- Docker를 사용할 수 없는 환경의 container test skip은 성공 증거가 아니다. 결과를
  `PENDING`으로 남기고 fresh Docker/Colima evidence를 별도로 수집한다.

## 검증 명령

```bash
./gradlew :optimization-warehouse-allocation:test --max-workers=1 --console=plain
./gradlew projects --console=plain
bash scripts/smoke-validate.sh optimization
actionlint .github/workflows/Examples.yml
git diff --check
```

진단 산출물은 `build/reports/warehouse-allocation-diagnostics/`에, 성능 JFR은
`build/reports/performance/*.jfr`에 redacted 상태로 보관한다. 전체 모듈 등록과 workflow
검증은 module test와 별도의 DoD 항목이다.

## 최종 검증 증거 (2026-08-24)

- [x] `:optimization-warehouse-allocation:test` 통과: 5개 XML, 13개 테스트,
  failures/errors/skipped 0개. PostgreSQL Testcontainers 저장소 검증 6개를 포함한다.
- [x] `bash scripts/smoke-validate.sh optimization` 통과: planning-contracts,
  field-service-dispatch, warehouse-allocation 검증을 순차 실행했다.
- [x] `./gradlew projects --console=plain`에서 세 optimization 모듈이 모두 노출되고,
  `actionlint .github/workflows/Examples.yml`, README 언어 검사, `git diff --check`가
  통과했다.
- [x] `testFixturesJar`와 `bootJar` 경계를 확인했다. fixture ABI는 test-fixtures JAR에만
  있고 production boot JAR에는 포함되지 않는다.
- [x] Colima/Docker context와 Docker daemon을 확인한 뒤 container-backed 테스트를
  실행했다. skip 결과를 성공으로 세지 않았다.
- [ ] `:optimization-warehouse-allocation:detekt`는 이 저장소의 해당 모듈에 task가
  등록되어 있지 않아 실행할 수 없었다. 정적 분석은 별도 build convention 정비가
  필요하다.

## 남은 범위

HTTP controller의 전체 Testcontainers end-to-end/stress suite와 모든 mutation route의
공통 idempotency service wiring은 후속 hardening 항목으로 남긴다. Issue #530의 경계대로
production WMS/로봇/운송사 연동과 실제 Timefold Solver provider는 포함하지 않는다.
