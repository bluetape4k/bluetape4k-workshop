# Issue #91 — Workshop Validation Matrix

## 배경

Epic #76 구조 개편은 모듈을 추가, 제거, 변환한다. 각 변경 wave 이후에도
workshop이 build 가능하고 test 가능하도록 유지하고, full suite 대신 targeted
smoke command를 제공하기 위한 validation framework가 필요했다.

## 결정

3-tier validation model을 사용한다.

| Tier | Trigger | Scope |
|------|---------|-------|
| T1 Compile | Every push/PR (CI) | All 56 modules, compile only |
| T2 Smoke | Daily nightly (Mon–Sat) | 23 no-Testcontainers modules |
| T3 Full | Weekly nightly (Sunday) | All 56 modules + Testcontainers |

산출물은 다음과 같다.

- `docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md` — module
  list와 domain별 command를 포함한 full matrix
- `scripts/smoke-validate.sh` — domain-group runner(`all-smoke`, `data-access`,
  `spring-boot`, `serialization`, `messaging`, `async`, `observability`, `redis`,
  `stale-check`)
- `.github/workflows/nightly.yml` — no-Testcontainers 모듈을 매일 실행하는
  `smoke-test` job(T2) 추가

## 결과

- 활성 모듈 56개 확인(stale Gradle include 없음)
- smoke-safe 모듈 23개 식별(Testcontainers 없음)
- archived module에 대한 stale README reference 0건
- 깨진 README 이미지 링크 0건. Markdown `![](path "title")`의 title string이 더
  이상 false positive를 만들지 않도록 regex를 수정했다.
- actionlint: nightly.yml OK

## 검증

```bash
./gradlew projects --no-daemon -q | grep -c "^+---"  # → 56
bash scripts/smoke-validate.sh stale-check            # → 0 stale, 0 broken
actionlint .github/workflows/nightly.yml              # → OK
```

## 향후 지침

- Epic #76의 각 wave(delete/add/convert) 이후 PR 생성 전에 `stale-check`를
  다시 실행한다.
- 새 Testcontainers 모듈을 추가하면 validation matrix spec의 T3 Full group에
  넣는다.
- `optimization-warehouse-allocation`은 PostgreSQL 권위 재고 예약을 검증하는
  Testcontainers 모듈이므로 T3와 `scripts/smoke-validate.sh optimization`에
  함께 등록한다.
- 새 no-Testcontainers 모듈을 추가하면 spec의 T2 Smoke list와
  `scripts/smoke-validate.sh all-smoke` + `nightly.yml smoke-test`에 함께 넣는다.
- broken-link regex는 optional Markdown image title string을 file path 일부로
  capture하지 않도록 `[^)]+`가 아니라 `[^ ")\t]+`를 사용해야 한다.
