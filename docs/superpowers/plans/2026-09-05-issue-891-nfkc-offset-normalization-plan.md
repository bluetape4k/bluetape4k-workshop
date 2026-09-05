# #891 NFKC offset·normalization 구현 계획

## 순서

1. `origin/develop` exact head worktree에서 Issue #891, GNO, upstream #244/#279,
   BOM 2.0.0과 대상 모듈 baseline을 확인한다.
2. NFC default 호환성, NFKC 원문 range, 1,024 segment bound와 raw-free 실패 경계를
   설계·사전 리뷰로 고정한다.
3. TDD red 단계에서 filter, redaction pipeline, Spring property binding의 NFKC와 bound
   회귀 테스트를 먼저 추가한다.
4. normalization option을 immutable filter/policy/property에 연결하고 기존 API와 HTTP
   schema를 유지한다.
5. EN/KO README, coverage matrix, ecosystem manifest, stale guard와 lesson을 갱신한다.
6. clean targeted tests, detekt, README language/parity, stale-check, ecosystem unit/scope,
   actionlint, dependency insight와 diff-check를 실행한다.
7. implementation six-lens review에서 P0/P1을 닫고 Lore commit/push 후 한국어 PR을
   생성한다. exact-head hosted CI와 metadata 확인 뒤 #892로 이동한다.

## 파일 소유 범위

- `kotlin/text-processing/src/{main,test}/**`
- `spring-boot/text-moderation-api/src/{main,test}/**`
- 두 module `README.md`, `README.ko.md`
- root `README.md`, `README.ko.md`, `docs/coverage-matrix.md`,
  `docs/ecosystem-reuse-train.json`, `scripts/smoke-validate.sh`
- `docs/superpowers/{specs,plans}`, `docs/review`, `docs/lessons`

## 검증 명령

```bash
./gradlew :kotlin-text-processing:cleanTest :kotlin-text-processing:test \
  :spring-boot-text-moderation-api:cleanTest :spring-boot-text-moderation-api:test \
  --no-build-cache --rerun-tasks --max-workers=1
./gradlew detekt --no-build-cache --rerun-tasks --max-workers=1
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs kotlin/text-processing spring-boot/text-moderation-api
bash scripts/smoke-validate.sh stale-check
python3 .github/scripts/test_check_ecosystem_reuse.py -v
actionlint .github/workflows/Examples.yml
git diff --check
```

## 완료 증거

- [x] Issue/GNO/upstream API/BOM 2.0.0 baseline 확인
- [x] NFC default·NFKC 원문 range·bounded failure 설계와 사전 review 완료
- [x] red tests와 normalization option 구현
- [x] EN/KO 문서·coverage·manifest·stale guard·lesson 갱신
- [x] clean tests 76/76, detekt, README, BSD/GNU stale guard, ecosystem/actionlint와 implementation review 완료
- [ ] PR exact-head hosted CI와 metadata 확인
