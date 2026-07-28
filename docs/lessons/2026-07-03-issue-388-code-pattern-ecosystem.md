# Issue #388 Code Pattern Ecosystem Cleanup

Date: 2026-07-03

## 배경

milestone 1.3.1 review는 기존 workshop code가 raw JDK 또는 generic test API 대신
bluetape4k ecosystem helper를 적극적으로 사용하는지 확인하는 pass를 요청했다.

## 결정

먼저 좁고 source-backed한 대체를 적용한 뒤, cleanup 완료를 선언하기 전에 repository-wide
scan을 실행한다.

- Opaque string ids use `Base58.randomString(8)` instead of embedding `UUID.randomUUID()`.
- Touched tests use `io.bluetape4k.assertions.assertFailsWith` instead of `kotlin.test.assertFailsWith`.
- Touched value-object validation uses `bluetape4k.support.require*` helpers.
- Production null assertions use `requireNotNull` / `requireNotBlank` for caller-facing values and `checkNotNull` for persistence, observation, and singleton invariants.
- runtime debug output은 `println` 대신 bluetape4k lazy logging을 사용한다.

## 결과

원래 issue module과 더 넓은 safe-production sweep은 이제 UUID generation, touched assertion,
touched validation, production `!!`, runtime `println`, stale commented example에 대해
code-pattern skill과 정렬된다.

## 향후 guard

raw Testcontainers 사용을 변경하기 전에 test가 isolated container, custom Docker network,
network alias, explicit failure mode를 의도적으로 필요로 하는지 확인한다. launcher singleton이
test를 약화한다면 exception을 기록한다.

좁은 grep만으로 repo-wide code-pattern cleanup을 주장하지 않는다. 최소한 모든 tracked Kotlin
file에서 `!!`, raw `require`, `Thread.sleep`, `runBlocking`, raw Testcontainers constructor,
legacy assertion import, raw UUID generation을 scan한다. virtual-thread sleep,
blocking-to-suspend bridge, test assertion rewrite처럼 behavior-sensitive한 예제는 focused
issue로 분리한다. 이 pass는 #390, #391, #392를 만들었다.

## 검증

- repository scan은 Kotlin file 1,473개를 다뤘다. 수정 후 production `!!`, raw
  `GenericContainer`, legacy assertion import, raw UUID generation은 0건이다.
- touched module 11개에 대한 targeted compile이 통과했다.
- touched module 11개에 대한 targeted test가 하나의 serial Gradle run에서 통과했다.
- `git diff --check`가 통과했다.
