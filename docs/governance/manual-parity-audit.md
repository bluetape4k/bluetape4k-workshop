# Manual 언어 쌍 검증 기록

## 목적

이 문서는 이슈 #599의 산출물이다. 이슈 #587의 primary rewrite scope에서
`docs/manual/en/**`와 `docs/manual/ko/**` 같은 bilingual manual pair를 제외한다는
결정을 현재 repository snapshot으로 검증한다.

## 현재 상태

현재 `bluetape4k-workshop` repository에는 `docs/manual/en`과 `docs/manual/ko`
디렉터리가 없다. 따라서 이번 Epic에서 manual pair를 재작성할 primary 대상도 없고,
manual parity defect도 없다.

```text
manual_parity=PASS
manual_pair_count=0
reason=manual directories are absent in this repository snapshot
```

## 적용 범위

- `README*` 파일은 계속 제외한다.
- `AGENTS.md`, `CLAUDE.md`, prompts, skills, workflow guidance는 영어 유지 대상이다.
- manual directory가 이후 추가되면 `node scripts/validate-korean-rewrite-scope.mjs manual-parity`
  결과를 기준으로 EN/KO basename parity를 먼저 확인한다.
- manual content rewrite가 필요해지는 경우 이번 이슈가 아니라 별도 child issue나 PR로 분리한다.

## DoD

- manual directory 부재를 현재 source tree에서 확인했다.
- manual parity validator가 PASS를 반환했다.
- 이번 PR은 manual content를 변경하지 않는다.

