# Issue 390 Raw Validation Helper Cleanup

## 배경

milestone 1.3.1 review가 bluetape4k ecosystem helper 사용의 불일치를 발견한 뒤,
Issue #390은 workshop example의 raw caller-input validation을 정리했다.

## 결정

- blank string, non-empty collection, positive/zero-positive number, bounded numeric range 같은
  단순 caller-input check만 bluetape4k helper로 변환한다.
- security predicate, parser boundary check, domain invariant, 정확한 detail text를 보존해야
  하거나 sensitive input echo를 피해야 하는 user-facing message에는 raw `require(...)`를
  유지한다.
- decimal-specific helper가 생길 때까지 exact `BigDecimal` comparison은 raw로 유지한다.
  generic numeric helper는 numeric conversion 과정에서 precision을 잃을 수 있다.
- production code가 `io.bluetape4k.support`를 import하는 module에는 direct
  `implementation(libs.bluetape4k.core)`를 추가한다.

## 결과

- `src/main` raw `require(...)` count는 `151`에서 `111`로 줄었다.
- 영향받은 production file의 raw `require(...)` count는 `92`에서 `52`로 줄었다.
- redaction pipeline의 blank-text check는 raw로 유지했다. `requireNotBlank`가 exception
  message에 raw blank value를 포함해 non-echoing test contract를 위반하기 때문이다.
- OCR controller는 기존 oversize message를 유지했다. HTTP error detail이 test로 보호되기
  때문이다.

## 검증

- 작업 전 baseline: clean `develop`에서 full
  `./gradlew build --max-workers=1 --console=plain`이 통과했다.
- affected-module `compileKotlin`은 `--max-workers=1 --warning-mode all`로 통과했다.
- affected-module `test`는 `--max-workers=1 --warning-mode all`로 통과했다.
- review fix 이후 post-work full
  `./gradlew build --max-workers=1 --warning-mode all --console=plain`이 통과했다.
- `git diff --check`가 통과했다.

## 향후 guard

모든 raw `require(...)`를 기계적으로 교체하지 않는다. 먼저 predicate를 분류한다.

- helper: 단순 caller input validation.
- explicit raw require: security, parser, domain invariant, exact public error message 또는
  sensitive-value non-echoing contract.
