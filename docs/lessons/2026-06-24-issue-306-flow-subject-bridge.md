# Lessons Learned — Issue 306 Flow Subject Bridge (2026-06-24)

**Related issue**: #306
**Affected module**: `:flow-extensions-subject-bridge`

## L1: Subject example에는 명시적 subscriber readiness가 필요하다

### 문제

`PublishSubject`는 subscription 이전 event를 버리고, `MulticastSubject`는 기대한
collector가 등록될 때까지 producer 진행을 suspend할 수 있다. collector를 launch한 직후
emit하는 workshop example은 flaky해지거나 잘못된 contract를 가르칠 수 있다.

### 교훈

예제의 핵심이 active hot-stream subscriber에 의존한다면 example과 test에서
`awaitCollector` / `awaitCollectors`를 사용한다.

## L2: Subject mutation은 bridge boundary 뒤에 둔다

### 문제

Subject instance를 직접 노출하면 코드는 짧아지지만, application code가 여러 곳에서 hot
stream을 mutate하도록 유도한다.

### 교훈

workshop example은 read-only `Flow` view와 callback-style bridge method를 노출해야 한다.
README는 backing Subject type을 이름으로 언급할 수 있지만, arbitrary mutation이 일반적인
application architecture처럼 보이게 해서는 안 된다.
