# Nightly mock 서버 이미지는 소비자 BOM 릴리스에 고정한다

## 배경

워크숍의 `gradle/libs.versions.toml`은 `io.github.bluetape4k:bluetape4k-dependencies:2.0.0`을
기준으로 사용한다. Nightly는 Testcontainers용 mock 서버 이미지를 별도로 빌드하므로
이미지 소스 ref도 같은 릴리스 선을 가리켜야 한다.

## 발견한 문제

Nightly가 `bluetape4k-projects`의 `develop`을 checkout했다. 현재 `develop`은 2.1.0
이미지를 만들지만 워크숍의 Testcontainers 헬퍼는 `bluetape4k/mock-web-server:2.0.0`과
`bluetape4k/mock-webflux-server:2.0.0`을 요청한다. 그 결과 이미지 빌드는 성공해도 테스트가
2.0.0 이미지를 pull하다가 실패했다.

## 결정

`.github/scripts/nightly-mock-images.py`가 소비자 BOM 버전을 읽어 mock 서버 소스 checkout에
전달한다. 현재 공식 `2.0.0` tag를 선택하며, snapshot이나 branch 이름은 거부한다.
이미지 빌드 직후 두 소비자 태그를 `docker image inspect`로 확인하여 테스트 전에 실패를 감지한다.
소비자 BOM을 올릴 때는 헬퍼 artifact 버전과 두 mock 이미지 tag를 함께 확인하고, `develop`
checkout을 사용하지 않는다.

## 결과

- 공식 `bluetape4k-projects` `2.0.0` tag의 `BluetapeHttpServer.TAG`와
  `BluetapeWebfluxServer.TAG`가 모두 `2.0.0`임을 확인했다.
- Nightly workflow가 이제 두 이미지를 2.0.0 소스에서 빌드한다.
- 저장소 전체 Gradle 선언에는 직접 bluetape4k 버전 좌표나 개별 bluetape4k BOM import가 없다.

## 검증

- 실패 run `33950336713`: `develop` 소스에서 2.1.0 이미지를 빌드한 뒤
  `bluetape4k/mock-web-server:2.0.0` pull이 404로 실패했다.
- `./gradlew :shared:dependencyInsight --dependency bluetape4k-testcontainers --configuration testRuntimeClasspath`:
  `bluetape4k-testcontainers`와 Spring 변형 모두 2.0.0으로 해석됐다.
- `actionlint .github/workflows/nightly.yml`: 통과.
- 수정 PR head `26c23afe2e92f00f922252015b5b9d4bfa0afc0c` 수동 Nightly `33959370148`:
  wrapper/compile-only와 mock 이미지 빌드는 통과했다. 전체 실행은 별도 graph 테스트 4건과
  job-core `slow-provider` 검사 실패로 종료됐다.

## 함께 확인한 테스트 경계

- 존재하지 않는 TinkerGraph 정점은 유효한 숫자 ID로 조회한다. 문자열 형식 오류와
  존재하지 않는 ID를 혼동하지 않는다. 해당 수정 후 graph 테스트 45건이 통과했다.
- 일시 정지한 worker의 연결 수는 그 worker 전용 풀에서 측정한다. 병렬 작업이 사용하는
  전체 풀을 측정하면 정상 연결도 누수로 오판한다. core도 Spring/Ktor의 전용 풀 방식을 따른다.
- 기존 train의 넓은 경로 규칙이 일반 버그 수정 PR과 충돌하면 정확한 head/base와 파일 목록을
  후속 범위로 등록한다. 이번 수정은 검사 비활성화나 과거 train 전체 상태 개편을 포함하지 않는다.

## 향후 지침

Nightly에서 외부 `bluetape4k-projects` 소스를 checkout할 때는 워크숍 소비자 BOM과 같은
release tag를 사용한다. BOM 또는 mock 서버 release를 변경하면 catalog 해석,
`Bluetape*Server.TAG`, 이미지 build log를 한 번에 확인하고, 독립적인 high-contention이나
graph 실패와 이미지 버전 불일치를 분리해서 보고한다.
