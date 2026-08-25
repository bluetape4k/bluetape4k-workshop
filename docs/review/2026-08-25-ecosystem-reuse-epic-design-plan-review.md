# ecosystem 재사용 Epic 설계·계획 통합 review

## 검토 범위와 결론

이 문서는 `bluetape4k/bluetape4k-workshop`의 #792 Epic 설계, 실행 계획,
9-track manifest, inventory/checker, 격리 workflow를 검토한 결과다. 기준은
`origin/develop`의 `7a1a94a5dd636978760d8bc5e1b92bc06656aab4`와
worktree branch `feat/ecosystem-reuse-gate`다. 검토 범위는 P0 foundation과
stack topology이며 Kotlin child 구현, Testcontainers 실행, PR CI와 merge는
범위 밖이다.

초기 판정은 **설계·계획 및 P0 계약 PASS / child 구현 PENDING**이었다. 후속
Type-C 수리에서 checker false positive와 PR scope gap을 발견했고, 현재는 해당
수리를 로컬 regression으로 검증한 상태다. 설계 승인과 live Epic mutation,
merge-ready, merged는 서로 다른 상태다. P0 산출물이 exact-head 검증을 통과한
뒤에만 #792 본문·labels·comments를 동기화하고, 자식 PR은 각자
`$bluetape-kotlin-patterns`, 7-Tier review, resolved dependency receipt와
targeted test receipt를 제출해야 한다.

## 6개 관점 결과

| 관점 | 판정 | 확인한 계약과 증거 | 남은 범위 |
|---|---|---|---|
| Performance | PASS | `--no-build-cache`, `--max-workers=1`, `--no-parallel`, track별 timeout, T1/I1 직렬 lock과 bounded cleanup | 실제 Gradle/Testcontainers elapsed와 resource receipt는 child에서 수집 |
| Stability | PASS | bootstrap은 정확한 9-track에만 허용, trusted manifest 비교, receipt/state 전이표, terminal receipt 불변 및 `PENDING` reset, R1→R2 symbol-level `parent_evidence`와 P0 reparent, active path overlap 거부 | 실제 ancestor OID/merge-base는 P0 commit 후 동결 |
| Security | PASS | 40-character action SHA와 pins file exact match, `contents: read`, token/secrets 금지, path traversal·symlink·control character 거부, sanitized artifact | GitHub hosted run에서 최종 receipt read-back 필요 |
| Operator/Ops | PASS | workflow_dispatch parent fallback, 문서/manifest/build 파일 path trigger, cancellation/retention/timeout, mutation retry 금지와 field-specific recovery | live GitHub mutation과 CI 실행은 아직 대기 |
| Developer/API | PASS (수리 후 재검증) | inventory의 source/test-only `actual_import`, `candidate:` marker, `libs.*` alias 거부, track별 project/coordinate/configuration 1:1 `dependencyInsight`, BOM-only gate, R1/R2 경계와 child review artifact allowlist | 실제 resolved coordinate와 import 변경은 child 작업에서 증명 |
| User/Caller | PASS | 한국어 설계·계획, `bluetape4k-assertions` before/after 표, raw fallback 분류·사유, A2의 aws·commerce·operations·planning·voucher·browser 범위, Epic/child 추적성 | 자식 PR의 reader-facing 예제 변경은 PENDING |

초기 통합 review snapshot에는 P0/P1 finding이 0건으로 기록됐지만, 후속 live
재검토에서 dependency declaration false positive와 PR scope gap을 P1로 식별했다.
현재 checker·manifest·workflow·inventory·lesson 수리는 로컬 회귀 검증을
통과했으며, exact-head hosted check와 PR read-back 전까지는 P0 `READY`로
승격하지 않는다. P2/P3는 문서 계약에 흡수했으며, 실제 구현 lane에서 다시
발생하면 해당 Tier와 descendant를 `INVALID`로 전환한다.

## 후속 P1 수리 증거

- `actual_import`가 `build.gradle.kts`/catalog를 가리키거나 `capability_api`가
  `libs.*`인 released 행을 거부하도록 checker를 강화했다.
- dependency-only inventory 행은 `actual_import=N/A`와 `candidate:` marker로
  재분류했고, 실제 `Uuid`, `Jackson`, `VirtualThreads`, `shouldBeEqualTo`
  consumer는 source/test anchor로 교정했다.
- PR diff를 전체 경로로 수집하고, 하나의 manifest `allowed_paths` 및 exact
  base/head ref·OID에 묶는 `--pr-scope` 검사를 추가했다.
- `python3 .github/scripts/test_check_ecosystem_reuse.py -v`는 38개 테스트를
  통과했고, checker bootstrap·JSON·diff check도 통과했다.

## Stacked child hosted-check 계약

Issue #813의 live evidence에서 non-default base branch child PR에 hosted
check가 생성되지 않는 trigger gap을 확인했다. P0는 다음 계약으로 이를
보완한다.

- `Examples.yml`과 `ecosystem-reuse-gate.yml`의 `pull_request.branches` 제한을
  제거해 `feat/ecosystem-reuse-gate`와 후속 child base에도 check가 연결된다.
- `push.branches: [develop, main]`와 기존 `paths` 제한은 유지해 push 실행 범위와
  workload scope를 넓히지 않는다.
- 두 workflow 모두 `contents: read`, pinned action, `persist-credentials: false`
  경계를 유지하며 fork PR에 write 권한이나 secret handoff를 추가하지 않는다.
- A1/F1 child는 exact head OID와 check run을 live read-back한 뒤에만 `PASS`로
  전환한다. 미보고 또는 skipped check는 PASS가 아니다.

## Writer 및 문서 검증

| 검사 | 결과 | 증거 |
|---|---|---|
| SPW-01 독자·목적·출처·범위 | PASS | 설계·계획·live issue·inventory·manifest·현재 source 경계를 고정 |
| SPW-02 구조·선택·실패·수용 기준 | PASS | 9-track topology, 상태/receipt 전이, rollback, child DoD와 중단 조건 |
| SPW-03 한국어 기술 문체 | PASS | reader-facing 설명은 한국어, code/API/path/command/URL은 원문 보존 |
| SPW-04 사실·식별자·계약 대조 | PASS | dependency alias/resolved module, exact selector, action pin, path allowlist 재대조 |
| SPW-05 read-back·Markdown·공백 | PASS | `git diff --check`, JSON parse, placeholder scan, terminology audit |
| KO-01~KO-07 용어·자연스러움 | PASS | 설계·계획·inventory·coverage·통합 review 대상 audit 결과 findings=0 |

## P0 검증 명령

```text
python3 .github/scripts/test_check_ecosystem_reuse.py -v  # 38 tests PASS
python3 .github/scripts/check-ecosystem-reuse.py \
  --inventory docs/ecosystem-reuse-inventory.md \
  --manifest docs/ecosystem-reuse-train.json --bootstrap \
  --workflow .github/workflows/ecosystem-reuse-gate.yml  # PASS
python3 -m json.tool docs/ecosystem-reuse-train.json  # PASS
actionlint .github/workflows/Examples.yml .github/workflows/ecosystem-reuse-gate.yml  # PASS
git diff --check  # PASS
./gradlew --no-daemon --no-build-cache --max-workers=1 --no-parallel detekt  # PASS (110 tasks)
```

## 상태·진입 조건

| 상태 | owner | 필수 artifact/read-back | 다음 진입 조건 |
|---|---|---|---|
| 설계·계획 PASS | coordinator | 본 문서, 6-lens table, SPW/KO 결과 | P0 foundation commit |
| live Epic PENDING | coordinator | #792 전체 body/metadata와 8개 stable-marker comment preimage/read-back | P0 exact-head freeze 후 bounded mutation |
| child READY 전 | child owner | manifest OID/checksum, `dependencyInsight` receipt, Kotlin pattern checklist, 7-Tier artifact, targeted test receipt | P0/P1=0 및 skipped/disabled 없음 |
| MERGE_READY | coordinator | exact head/base/merge-base, CI/review/thread, mutation receipt | 새 merge approval |
| MERGED | GitHub owner | 실제 merge SHA와 train closeout | 본 계획 범위 아님 |

현재는 설계·계획 및 P0 contract만 PASS다. child Kotlin 구현과 live GitHub
mutation/PR/CI/merge를 PASS로 해석하지 않는다.
