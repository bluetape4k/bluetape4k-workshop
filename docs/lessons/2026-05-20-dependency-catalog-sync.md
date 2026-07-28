# 의존성 카탈로그 동기화

## 배경

`bluetape4k-dependencies` promoted MyBatis Dynamic SQL to 2.0.0, Timefold
Solver to 2.1.0, AWS SDK Java to 2.44.9, AWS SDK Kotlin to 1.6.77, and Fory
Kotlin to 0.17.0을 공유 카탈로그 버전으로 승격했다.

## 결정

다른 워크스페이스 체크아웃의 관련 없는 로컬 Windows wrapper 드리프트는 건드리지 않고,
두 공유 카탈로그 변경을 workshop 저장소에 반영한다.

## 결과

`gradle/libs.versions.toml`은 이제 승격된 의존성에 대해 중앙 카탈로그와 일치한다.

## 검증

- `./gradlew build -x test --no-daemon`

빌드는 기존의 관련 없는 경고만 남긴 채 완료되었다.
