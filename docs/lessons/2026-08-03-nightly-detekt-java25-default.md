# GitHub Actions에서 Detekt를 제외한 CI 정책

## 증상과 원인

Nightly의 `Build (compile only)`가 여러 commerce 모듈의 `detekt`에서 연속 실패했다. 로그의 공통 원인은 `> 25.0.3`이었고, Java 25에서 해당 모듈의 Detekt를 직접 실행하면 Detekt 1.23.8이 포함한 Kotlin/IntelliJ Java 버전 파서가 Java 25 patch version을 해석하지 못해 `IllegalArgumentException`을 던졌다.

`.github/workflows/nightly.yml`은 `setup-java`에 여러 JDK를 설치하고 마지막으로 설정한 JDK를 Gradle 기본 런타임으로 사용한다. Java 25 toolchain이 필요한 예제와 Java 25 전용 고경합 job을 유지하면서 Detekt를 계속 실행하는 것은 안정적인 CI 경로가 아니므로, Actions의 모든 Gradle 호출에서 Detekt를 명시적으로 제외한다.

## 유지할 가드

- 모든 workflow의 직접 Gradle 호출과 `scripts/smoke-validate.sh`의 Gradle 경로에는 `-x detekt`를 유지한다.
- `JAVA_VERSIONS`는 `21` 다음 `25` 순서로 두어 일반 Gradle 작업의 기본 런타임을 기존 설정으로 유지한다.
- `optimization/*`의 Java 25 toolchain과 전용 high-contention Java 25 job은 유지한다.
- Java 버전 목록이나 workflow Gradle 호출을 바꿀 때는 `actionlint`와 Detekt 제외 task graph를 함께 확인한다.
- `setup-java`의 다중 버전 동작은 [공식 문서](https://github.com/actions/setup-java)를 기준으로 검토한다.

## 검증

`build`, `test`, `highContentionCi`, `highContentionLocalReference` dry-run에서 `detekt` task가 나타나지 않았고, 워크플로 호출 전수 assertion, `actionlint`, `bash -n`, `git diff --check`도 통과했다.
