# Issue 303 Flow Event Aggregation Lesson

## 배경

event aggregation example은 Kafka, storage, HTTP infrastructure를 추가하지 않고 bounded
order-event replay를 위한 Flow operator boundary를 가르친다.

## 결정

`groupBy`를 명시적으로 finite하게 유지한다. `groupBy().toGroupItems()`는 완료된 각 group을
materialize하므로, distinct order id가 작은 concurrency cap을 초과할 때 hidden deadlock을
피하기 위해 example은 `flatMapMerge(concurrency = Int.MAX_VALUE)`와 high-cardinality timeout
test를 사용한다.

## 결과

module은 이제 bounded activity summary, rolling window, finite grouping, immutable
read-model projection, lifecycle run collapse, transition, sanitized audit logging,
cooperative cancellation을 다룬다.

## 향후 지침

이 `groupBy` pattern을 unbounded hot ingestion에 재사용하지 않는다. service pipeline으로
바꾸기 전 durable partitioning, checkpoint, backpressure, storage를 추가한다.
