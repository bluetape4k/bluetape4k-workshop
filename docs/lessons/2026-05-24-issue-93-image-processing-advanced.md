# Issue 93 Image Processing Advanced Workflow

## 배경

Issue #93은 Spring Boot 4, Java 25 FFM VIPS, `ImageStorage`, S3/local storage,
unsigned public URL, workflow metric으로 Bluetape4k image library를 보여주는
advanced image-processing workshop module을 추가했다.

## 결정

- example은 Bluetape4k-first로 유지한다. app code는 orchestration과 URL policy를 소유하고,
  Bluetape4k는 VIPS processing, object key, storage, health, upload metadata를 소유한다.
- native VIPS integration test는 `-Dvips.enabled=true`로 opt-in하게 한다. property가 없는
  native probe가 fatal native signal로 local JVM을 crash시켰으므로, deterministic CI default는
  native execution을 명시적으로 건너뛴다.
- VIPS decode 전 JPEG/PNG/WebP allowlist와 magic byte로 upload를 검증한 뒤, final image gate는
  VIPS `maxPixels`와 decode error에 맡긴다.
- 명시적으로 허용하지 않은 unsafe `publicBaseUrl` setting을 거부한다: non-HTTPS remote URL,
  userinfo, query/fragment, parent path traversal, local-storage remote URL.

## 결과

새 `:image-processing-advanced-workflow` module은 upload API, variant workflow,
public URL resolver, metric, storage cleanup, README architecture/sequence diagram,
root README/smoke script integration을 포함한다.

## 검증

- `./gradlew :image-processing-advanced-workflow:build -Dvips.enabled=false --console=plain`
  통과: test 21개, native VIPS test 1개 skipped.
- `./scripts/smoke-validate.sh stale-check` 통과.
- 같은 branch에서 앞서 `./scripts/smoke-validate.sh all-smoke` 통과.
- 같은 branch에서 앞서 `./gradlew build -x test --parallel --continue --console=plain` 통과.
- `git diff --check` 통과.
- Claude review gate artifact가 `P0=0 P1=0`에 도달:
  `.omx/artifacts/claude-issue-93-code-review-final2-20260524171319.md`.

## 향후 참고

- 향후 작업에서 variant format을 configurable하게 만들면, 현재 WebP-only
  `contentType`/`extension` invariant를 제거하고 encoder format을 config에서 구동한다.
- discovery나 initialization 중 host image stack이 JVM을 종료할 수 없음을 증명하지 않고
  native VIPS test를 default로 활성화하지 않는다.
