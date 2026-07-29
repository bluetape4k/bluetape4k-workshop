# Issue 348 - Kafka-first outbox fallback

## 배경

Issue #348은 transaction에는 `orders`만 저장하고, commit 이후 event를 Kafka에 publish하며,
direct publication이 실패하거나 reconciliation이 missing row를 복구할 때만
`event_publications`를 쓰는 방식으로 hot transaction write cost를 낮추는 workshop module을
추가했다.

## 결정

module은 의도적으로 classic transactional outbox를 primary path로 구현하지 않는다. direct
Kafka success는 publication row를 남기지 않는다. failure, timeout, disabled publish,
reconstructed gap은 relay가 claim하고 다시 drive할 수 있는 `NOT_PUBLISHED` row를 만든다.

## 작업 중 실패한 것

- architecture diagram의 rounded connector path는 visual inspection을 통과했지만,
  Q-bend control point가 zero-length pre/post leg로 collapse되면서 geometry audit에 실패했다.
- architecture validator는 `M`/`L` path point만 parse했기 때문에, 유효한 Q-bend rounded
  connector가 diagonal false positive로 보고되었다.

## 해결 증거

- `diagram-geometry-audit.py`: `geometry_failures=0` for the touched
  architecture and state diagrams.
- `diagram-mixed-corner-audit.py`: `q_bends=10 failures=0`.
- `validate-readme-architecture-diagrams.mjs`: `checked=99`, `failures=0`
  after parsing Q control/end points as route waypoints.
- `validate-sequence-diagrams.mjs`: `checked=74`, `failures=0`.
- Full-size PNG inspection confirmed no card/label/connector overlap in the
  architecture, sequence, and state diagrams.

## 향후 guard

connector-heavy README diagram에서는 Q bend geometry, mixed-corner, endpoint, XML,
CairoSVG render, full-size PNG check가 모두 통과하기 전까지 "looks good"만으로 수용하지
않는다. repo-local validator가 rounded connector를 parse하지 못하면 diagram geometry를
약화하지 말고 validator를 좁게 수정한다.
