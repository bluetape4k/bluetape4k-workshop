# AWS README diagram refresh

## 배경

AWS workshop README refresh는 Graphviz-era asset을 root `aws` module,
`s3-spring-cloud`, `storage-abstraction`의 source-backed README diagram으로 교체했다.

## 결정

- README diagram은 repository의 `docs/images/readme-diagrams/` asset directory 아래에
  유지하되, obsolete Graphviz `.dot`, `.plain`, `*-graphviz.*` asset은 제거한다.
- S3 card에는 official AWS service icon을 사용하고, Testcontainers/Floci runtime card에는
  shared wiki icon을 사용한다.
- sequence diagram은 architecture diagram이 아니라 behavior view로 다룬다. participant
  header, lifeline, branch frame, numbered message label, 절제된 arrow에는
  `leader-core-sequence-02` visual family를 사용한다.

## 결과

AWS module README는 이제 reader viewpoint에서 두 가지 local-first S3 path를 설명한다.
S3 sequence는 낮은 가치의 log-only participant를 생략하고, `S3Client`, `S3Template`,
S3, Floci를 통과하는 실제 bucket/object flow를 보여준다. storage abstraction module은
static profile-selection architecture view와 request sequence를 함께 제공하며, `alt`
region을 반투명하게 유지해 lifeline, call, label이 branch frame 너머로도 읽히게 한다.

## 검증

- 변경된 SVG asset을 CairoSVG로 PNG로 렌더링했다.
- AWS overview, S3 architecture, S3 sequence, storage abstraction diagram을 시각 검사했다.
- README image link, SVG XML parsing, Graphviz 잔재 없음, `git diff --check`를 검증했다.

## 다음 작업자 지침

README diagram refresh를 수용하기 전에는 SVG source만 보지 말고 최종 PNG를 검사한다.
late layout change 이후 icon이 label을 쉽게 가릴 수 있으므로 commit 전에 card를
resize하거나 이동한다.
layer에 title/detail text가 있으면 inner card를 가운데 정렬하기 전에 해당 label band를
예약한다. card가 시각적으로 crowd되거나 label area를 덮는다면 같은 outer layer margin만으로는
충분하지 않다.
