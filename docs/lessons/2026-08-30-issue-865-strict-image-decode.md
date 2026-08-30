# Context

`image-processing/barcode-api`는 업로드 bytes를 자체 probe로 확인한 뒤
`immutableImageOf`로 디코드하고 provider-neutral `BarcodeReader`에 전달했다. 이 경계만으로는
probe가 차원을 알 수 없거나 실제 decoder 결과가 probe와 달라지는 입력을 일관되게 제한하기
어렵다. upstream `bluetape4k-images`의 `immutableExternalImageOf`와 `ImageDecodeLimits`를
`2.0.0-SNAPSHOT` 소비자 예제에 적용할 필요가 있었다.

# Decision or Finding

`BarcodeExampleProperties`의 기존 정책인 encoded 2 MiB, decoded 12M pixels, 최대 side 4096을
`ImageDecodeLimits`로 그대로 전달한다. 서비스의 bounded dimension/metadata 사전 확인은
기존 HTTP `413` 계약을 유지하기 위해 남기고, 그 확인을 통과한 뒤에도
`immutableExternalImageOf(bytes, limits)`를 provider 호출 직전의 최종 decode 경계로 사용한다.
따라서 strict helper가 제한 없는 decoder 호출 전에 encoded 크기와 unknown/oversized dimension을
거부하고, 실제 decode 결과도 다시 확인한다. provider 예외와 coroutine cancellation은 서비스의
기존 계약대로 재매핑하지 않는다.

# Outcome

PNG와 JPEG 정상 입력, WebP 정상 입력과 metadata fallback, malformed/unknown dimension 입력,
encoded/pixel/side 초과, strict loader의 실제 dimension 재확인, provider-neutral 예외 identity,
cancellation 전파를 barcode 서비스 테스트로 고정했다. README 양언어 문서에는 BOM 버전 관리와
사전 확인/strict helper의 역할을 함께 기록했다.

# Verification

다음 targeted 검증이 통과했다.

```text
./gradlew :image-processing-barcode-api:test --no-daemon --no-build-cache --console=plain
SUCCESS: Executed 17 tests
BUILD SUCCESSFUL
```

# Future Guidance

외부 업로드 bytes를 이미지 decoder에 전달하는 예제는 provider 호출 전에
`immutableExternalImageOf`와 `ImageDecodeLimits`를 사용하고, HTTP 상태 계약을 보존해야 하는
사전 검증이 있다면 두 경계의 목적을 문서와 테스트에 분리해 기록한다. 새 이미지 포맷을
추가할 때는 정상 decode뿐 아니라 malformed, unknown dimension, encoded/pixel/side limit,
cancellation 회귀를 함께 확장한다.
