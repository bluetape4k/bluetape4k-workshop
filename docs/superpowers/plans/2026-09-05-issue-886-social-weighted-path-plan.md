# #886 social-network weighted shortest path 구현 계획

## 순서

1. #885 exact head `df05468…` 위에 격리 worktree를 만들고 `bluetape4k-graph 2.0.0`
   BOM과 versionless graph alias를 확인한다.
2. upstream `PathOptions`/Dijkstra 계약과 Issue #559 evidence를 문서화하고
   design review를 남긴다.
3. sync/suspend 서비스에 weighted wrapper와 bounded options를 추가한다. 기존 hop
   API와 `strength` 저장 계약은 유지한다.
4. TinkerGraph abstract tests를 먼저 작성해 weighted-vs-hop, totalWeight, maxDepth,
   maxVisited, direction, missing/invalid weight, deterministic tie, sync/suspend parity를
   고정한다.
5. Neo4j/Memgraph integration test가 같은 wrapper 계약을 재사용하는지 확인하고
   container 실행 명령과 제외 규칙을 문서화한다.
6. root/module EN·KO README, coverage matrix, Examples workflow/stale-check,
   ecosystem manifest와 lesson을 갱신한다.
7. targeted/full tests, detekt, README language/parity, stale-check, actionlint,
   ecosystem scope, diff-check를 실행하고 implementation review에서 P0/P1/P2를
   닫는다.
8. Lore commit으로 push하고 PR metadata/body를 exact head로 검증한다. hosted CI 전체
   PASS 전에는 #887로 이동하지 않는다.

## 파일 소유 범위

- `graph/social-network/src/main/**`
- `graph/social-network/src/test/**`
- `graph/social-network/README.md`, `README.ko.md`
- root `README.md`, `README.ko.md`, `docs/coverage-matrix.md`,
  `docs/ecosystem-reuse-train.json`, `scripts/smoke-validate.sh`,
  `docs/superpowers/{specs,plans}`, `docs/lessons`

## 검증 명령

```bash
./gradlew :graph-social-network:test --no-build-cache --rerun-tasks --max-workers=1
./gradlew detekt --no-build-cache --rerun-tasks --max-workers=1
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs graph/social-network
bash scripts/smoke-validate.sh stale-check
git diff --check
```

## 중단/복구

- BOM에서 weighted `PathOptions`가 resolve되지 않으면 우회 구현을 만들지 않고
  dependency evidence를 수집한다.
- TinkerGraph가 통과하고 container backend가 실패하면 backend별 원인을 분리하되
  partial path를 허용하지 않는다.
- cumulative ecosystem gate가 새 graph 경로를 누락하면 manifest receipt/hash를
  갱신한 뒤 local/hosted gate를 exact head에서 재실행한다.
