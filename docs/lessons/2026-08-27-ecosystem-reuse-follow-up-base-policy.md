# G0-BASE-POLICY 후속 child base 정책 교훈

## 배경

PR #831의 branch에는 후속 child base 정책에 필요한 checker/test 변경이
있었지만, 최신 `develop`과의 diff에는 이미 병합된 closeout 상태를 되돌릴 수
있는 historical 변경도 섞여 있었다. 따라서 전체 branch를 cherry-pick하거나
rebase하지 않고, `origin/develop@69ba991778101c027282a80726de5eb1403da091`에서
새 G0 lane을 시작했다.

## 결정

1. follow-up scope는 `base_ref_policy`를 반드시 선언한다.
2. `parent-head`는 기존 child/coordinator scope의 parent expected head만
   허용한다.
3. `repository-base-after-parent-merge`는 실제 child가 최신 repository
   base에서 rebase되는 경우에만 허용하고, `oid_policy=rebase-aware`와
   함께 사용한다.
4. 이미 병합된 #821/#832/#833의 code, receipt, inventory 상태는 이 lane에서
   다시 열거나 재생성하지 않는다.
5. 후속 예제 개선에서는 module별 `$bluetape-kotlin-patterns`와
   `bluetape4k-assertions` 사용을 별도 source/test lane의 acceptance로
   유지한다. 이번 governance lane에서 Kotlin 파일을 억지로 변경하지 않는다.

## TDD와 검증 교훈

테스트 fixture에 `base_ref_policy`와 positive/negative 사례를 먼저 넣었을
때, 구현 전 기존 checker는 `unknown fields base_ref_policy` 및 정책 거부로
6개 테스트를 실패시켰다(RED). 그 뒤 enum, 필수 필드, scope-kind/OID,
expected-base 분기만 최소 구현해 90개 전체 테스트를 `OK`로 만들었다(GREEN).

다음 명령을 재현 가능한 raw fallback으로 보존한다.

```text
python3 .github/scripts/test_check_ecosystem_reuse.py -v
python3 .github/scripts/check-ecosystem-reuse.py --inventory docs/ecosystem-reuse-inventory.md --manifest docs/ecosystem-reuse-train.json --workflow .github/workflows/ecosystem-reuse-gate.yml --pins docs/governance/github-action-pins.json
python3 -m json.tool docs/ecosystem-reuse-train.json
python3 -m py_compile .github/scripts/check-ecosystem-reuse.py .github/scripts/test_check_ecosystem_reuse.py
git diff --check
```

최신 base에 대한 새 Type E workflow receipt
`20260827T133807Z-2e478eff`를 발행했으며, sequence 16 head
`efde1f6ec96c2531e8a7dc3844805f69742eeab6e69d30e894a4abacfcfc38cd`를
manifest와 함께 기록했다. `completion-check`는 required
component/lane/check/replacement/main proof 누락 없이 `complete=true`를
반환했다.

## 재발 방지

- 부모 merge 직후 child를 만들 때는 parent branch ref와 repository base를
  먼저 구분하고, 정책과 OID policy를 함께 기록한다.
- manifest scope 또는 allowlist를 바꾸면 기존 receipt를 재사용하지 말고
  fresh coordinator receipt를 발행한다.
- historical PR branch는 참고 자료로만 읽고, 최신 develop의 현재 manifest와
  trusted 비교를 기준으로 새 변경을 만든다.
- checker의 positive/negative 테스트와 7-Tier review/lesson을 같은 변경에
  포함하되, 실제 hosted CI는 exact PR head에서 다시 실행한다.

## Stop condition

unknown policy, scope-kind/OID 위반, repository/parent base 혼용, stale
receipt, P0/P1 review finding, exact-head CI 또는 fresh approval 부재 중 하나라도
남으면 다음 F2/R1/T1/I1 lane이나 merge로 진행하지 않는다.
