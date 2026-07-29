# Issue 304 Flow Metrics Sampling Lesson

## 배경

metrics sampling example은 metrics storage 또는 transport infrastructure를 추가하지 않고
noisy CPU, queue-depth, latency, sensor stream에 대한 Flow operator boundary를 가르친다.

## 결정

기존 `bluetape4k-coroutines` Flow extension을 직접 사용한다. alert preview에는
`throttleLeading`, dashboard에는 `throttleTrailing`, adjacent delta에는 `pairwise`,
lifecycle stop에는 `takeUntil`, observation에는 `Flow<T>.log()`, cancellation-safe Result
mapping에는 `mapResultCatching`을 사용한다.

## 결과

module은 이제 fast preview sampling, stable dashboard sampling, transition analysis,
lifecycle stop handling, cancellation behavior를 bilingual learner documentation이 포함된
작고 testable한 function으로 분리한다.

## 향후 지침

throttle operator의 channel-bound implementation detail을 workshop acceptance test로 만들지
않는다. throttle example에서는 user-facing sampling value를 테스트하고, explicit
cancellation contract는 `mapResultCatching`처럼 그 contract를 소유한 extension에서 테스트한다.
