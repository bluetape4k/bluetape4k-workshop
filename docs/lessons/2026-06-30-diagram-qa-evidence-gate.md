# Diagram QA evidence gate

## 배경

Issue #318은 반복되는 diagram workflow failure를 드러냈다. 개별 validator는 통과하지만
rendered diagram에는 여전히 connector clearance, rounded-bend, style-parity 문제가 남을 수
있었다. 가장 약한 failure mode는 audit output 자체가 script가 diagram shape를 이해하지
못했음을 보여주는데도 generic audit output을 충분한 증거로 취급하는 것이었다. 예를 들면
architecture diagram에서 `cards=0`, rounded connector file에서 `paths=0`이 나온 경우다.

## 결정

`scripts/validate-readme-diagram-qa.mjs`를 README diagram 변경의 repo-local evidence gate로
사용한다. 이 script는 변경된 README SVG diagram을 수집하고, XML을 검증하고, PNG를 다시
렌더링하고, 기존 architecture/sequence validator를 실행하고, `bluetape4k-diagram`
reference audit을 실행한다. fallback evidence가 없으면 weak audit row를 incomplete로
취급한다.

`scripts/smoke-validate.sh diagram-qa`는 일반 smoke runner를 통해 이 gate를 노출하고,
Examples workflow는 이를 `Diagram QA`로 실행한다.

## 결과

diagram review report는 이제 "checklist passed" 같은 prose 대신 구체 evidence value를
필요로 한다. connector-heavy diagram의 report에는 marker count, geometry/endpoint/crossing
result, rounded-bend fallback count, terminal segment length, rendered PNG path, visual
inspection note가 포함되어야 한다.

S3 Vectors Access Grants diagram의 경우 gate는 architecture reference audit을 `cards=0`과
`paths=0` 때문에 weak로 기록한다. 이후 fallback evidence가 `connectors=7`, `bent=3`,
`rounded_bent=3`, `sharp_bent_failures=0`,
`access-grants-to-object-data:33.0`을 보고했기 때문에만 asset을 수용한다.

## 검증

- `node --check scripts/validate-readme-diagram-qa.mjs`
- `./scripts/smoke-validate.sh diagram-qa`
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## 향후 지침

단일 validator 결과만으로 diagram checklist success를 보고하지 않는다. diagram이 변경되면
wrapper를 실행하고 evidence ledger를 PR body에 복사한다. row가 `WEAK`라면 diagram pass를
주장하기 전에 reference audit을 개선하거나 targeted fallback invariant를 추가한다. full-size
rendered PNG inspection은 별도의 human visual gate로 남는다. wrapper는 mechanical evidence를
증명할 뿐 taste를 증명하지 않는다.
