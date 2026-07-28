# Code scanning alerts

## 배경

GitHub CodeQL은 CI와 Nightly workflow token permission alert를 보고했고, sample
static resource와 check-in된 Gatling report의 JavaScript alert도 보고했다.

## 결정

먼저 workflow-level `contents: read` permission을 명시하고, 그 다음 CodeQL이 unsafe로
증명할 수 있는 static resource를 수정하거나 제거한다.

## 결과

workflow token default는 이제 checkout 기반 job에 대해 least-privilege다. static
resource 수정은 alert가 발생한 파일로 범위를 제한했다.

## 검증

- `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml`
- `yq` inspection of workflow permissions
- `git diff --check`

## 향후 guard

의도적으로 publish하는 artifact이고 security review가 static risk를 수용한 경우가
아니라면, bundled legacy JavaScript가 포함된 generated performance report를 commit하지
않는다.
