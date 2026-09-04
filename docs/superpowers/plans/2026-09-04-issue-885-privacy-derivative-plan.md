# #885 privacy-safe derivative 구현 계획

## 순서

1. 현재 #884 exact head 위의 격리 worktree와 root BOM/versionless alias를 확인한다.
2. privacy 설정과 처리 결과 report 타입을 추가하고, strict input metadata reader와
   `suspendPrivacyDerivative` adapter의 실패 테스트를 먼저 작성한다.
3. approved/pending derivative 생성에 upstream pipeline을 연결한다. blur, thumbnail,
   redaction geometry, orientation 순서를 결정적으로 유지한다.
4. 원본 불변성, metadata category, malformed writer, parser failure, cancellation,
   service cleanup 회귀 테스트를 추가한다.
5. module/root EN·KO README, application.yml, coverage matrix, Examples/stale-check
   경계와 lesson을 갱신한다.
6. targeted/full test, detekt, README language/parity, stale-check, diff-check를 수행하고
   implementation review artifact에서 P0/P1/P2를 수렴한다.
7. Lore commit으로 push하고 PR metadata/body를 live read-back한다. exact-head hosted
   CI가 녹색일 때만 다음 #886으로 진행한다.

## 파일 소유 범위

- `image-processing/profile-image-moderation/src/main/**`
- `image-processing/profile-image-moderation/src/test/**`
- module `README.md`/`README.ko.md` 및 `application.yml`
- root `README.md`/`README.ko.md`, `docs/coverage-matrix.md`,
  `docs/ecosystem-reuse-train.json`, `scripts/smoke-validate.sh`,
  `docs/superpowers/{specs,plans}`, `docs/lessons`

## 검증 명령

```bash
./gradlew :image-processing-profile-image-moderation:test --no-build-cache --rerun-tasks --max-workers=1
./gradlew detekt --no-build-cache --rerun-tasks --max-workers=1
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs image-processing/profile-image-moderation
bash scripts/smoke-validate.sh stale-check
git diff --check
```

## 중단/복구 규칙

- upstream API가 현재 BOM에서 resolve되지 않으면 코드 우회를 추가하지 않고 의존성
  resolution evidence를 수집해 범위를 재평가한다.
- hosted Ecosystem Gate가 cumulative stacked path를 인식하지 못하면 manifest에 해당
  issue scope와 expected head를 추가한 뒤 local checker와 exact-head CI를 재실행한다.
- 원본 cleanup/state lifecycle 테스트가 깨지면 privacy adapter만 수정하고 state/schema
  범위를 넓히지 않는다.
