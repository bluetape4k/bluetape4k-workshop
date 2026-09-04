# #887 knowledge-graph schema drift planner 구현 계획

## 순서

1. #886 exact head `1158633…` 위에 격리 worktree를 만들고
   `bluetape4k-dependencies` BOM `2.0.0` 및 versionless graph alias를 확인한다.
2. upstream `GraphSchemaDefinition`, `GraphSchemaPlanOptions`, `GraphSchemaManager.plan`
   및 TinkerGraph/Neo4j/Memgraph capability evidence를 설계 문서와 review에 기록한다.
3. desired schema 선언과 blocking/coroutine `planSchema`/`initialize` ordering을
   추가한다. 자동 DDL 적용은 구현하지 않는다.
4. TDD 순서로 TinkerGraph tests를 추가해 deterministic plan, dry-run no-mutation,
   unsupported constraint report, planner failure-before-seed 계약을 고정한다.
5. Neo4j/Memgraph integration abstract suite가 동일 API를 재사용하는지 확인하고
   Docker 실행/제외 규칙을 유지한다.
6. root/module EN·KO README, coverage matrix, Examples workflow/stale guard,
   ecosystem manifest, lesson을 갱신한다.
7. targeted/full tests, detekt, README language/parity, stale-check, actionlint,
   ecosystem scope, dependency insight, diff-check를 실행한다.
8. implementation review에서 P0/P1/P2를 닫고 Lore commit/push 후 PR metadata/body,
   hosted CI exact head와 리뷰 상태를 검증한다. 최종 다섯 PR 승인 전에는 merge하지 않는다.

## 파일 소유 범위

- `graph/knowledge-graph/src/main/**`
- `graph/knowledge-graph/src/test/**`
- `graph/knowledge-graph/README.md`, `README.ko.md`
- root `README.md`, `README.ko.md`, `docs/coverage-matrix.md`,
  `docs/ecosystem-reuse-train.json`, `scripts/smoke-validate.sh`,
  `.github/workflows/Examples.yml`
- `docs/superpowers/{specs,plans}`, `docs/lessons`

## 검증 명령

```bash
./gradlew :graph-knowledge-graph:test --no-build-cache --rerun-tasks --max-workers=1
./gradlew detekt --no-build-cache --rerun-tasks --max-workers=1
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs graph/knowledge-graph
bash scripts/smoke-validate.sh stale-check
python3 .github/scripts/test_check_ecosystem_reuse.py -v
python3 .github/scripts/check-ecosystem-reuse.py --manifest docs/ecosystem-reuse-train.json \
  --base-ref "$baseOID" --head-ref "$headOID" --pr-scope \
  --base-ref-name develop --head-ref-name feat/issue-887-knowledge-schema-drift
./gradlew :graph-knowledge-graph:dependencyInsight \
  --dependency io.github.bluetape4k.graph:bluetape4k-graph-core \
  --configuration testRuntimeClasspath
actionlint .github/workflows/Examples.yml
git diff --check
```

## 중단/복구

- BOM에서 graph schema planner API가 resolve되지 않으면 우회 API를 만들지 않고
  dependency evidence를 수집해 범위를 멈춘다.
- TinkerGraph 기본 테스트가 통과하고 container backend가 실패하면 backend별
  capability/DDL 원인을 분리하며 unsupported를 성공으로 바꾸지 않는다.
- cumulative ecosystem gate가 새 graph 경로를 누락하면 manifest receipt/hash와
  PR body exact-head evidence를 함께 갱신한 뒤 hosted CI를 재실행한다.
