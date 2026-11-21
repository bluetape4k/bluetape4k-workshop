# Image Barcode API

[한국어](README.ko.md) | English

This Spring Boot example consumes the `bluetape4k-images` barcode API through the
`bluetape4k-dependencies` `2.1.0-SNAPSHOT` BOM. Individual Bluetape module versions remain
versionless in the catalog. The application code stays provider-neutral and wires the ZXing
implementation only at the configuration boundary.

## Why this example exists

Barcode extraction sits on an untrusted upload boundary: a client wants a quick
scan result, while the decoder must not receive an oversized or malformed image
before the service can reject it. This example shows the business-shaped API
contract for that small but common ingestion step without coupling the
application service to ZXing.

The design keeps provider choice at configuration and puts bounded metadata and
pixel checks before the decoder, with the strict Bluetape helper as the final
defense. The deterministic fixture routes make success, no-result, malformed
input, and HTTP `413` behavior inspectable; they are not a production upload,
virus-scanning, or moderation policy.

`BarcodeExtractionServiceTest` and `BarcodeApiApplicationTest`, run by the
`test` command below, are the test-backed verification for this boundary. They
cover formats, limits, malformed input, provider failure, cancellation, and the
HTTP contract rather than claiming scanner accuracy for every camera or label.

## Business scenario and requirements

The business case is a parcel, ticket, or product-label upload: the caller
wants the decoded value, but the service must first decide whether it is safe to
spend decoder CPU on the file. A reader who is new to the example can follow
the request as four decisions:

1. Is the multipart payload present, non-empty, and within the encoded-byte
   budget?
2. Do the declared content type and image header agree, and are the decoded
   dimensions/pixels bounded?
3. Can the provider-neutral `BarcodeReader` inspect the already-bounded image?
4. Should the result be a barcode list, an explicit no-result response, or a
   stable error?

The application service owns decisions 1, 2, and 4. ZXing is only one adapter
for decision 3. That separation means a provider replacement cannot silently
change the HTTP size contract, and a camera image that contains no barcode is a
valid scan outcome rather than a server failure.

## Request and response examples

Run the application and send an actual image:

```bash
curl -i -F "file=@label.png;type=image/png" \
  http://localhost:8080/api/barcodes/extract
```

A successful response has a stable provider-neutral shape (the exact decoded
value depends on the input):

```json
{
  "barcodes": [
    { "format": "QR_CODE", "text": "https://example.com/order/42" }
  ]
}
```

An image with no readable code returns an explicit empty result. Unsupported,
malformed, or over-budget input is rejected before an unrestricted decoder call;
the service's own encoded-size preflight maps that boundary to HTTP `413`.
The deterministic fixtures make those outcomes easy to inspect:

```bash
curl http://localhost:8080/api/barcodes/sample
curl http://localhost:8080/api/barcodes/no-result
curl http://localhost:8080/api/barcodes/malformed
```

The fixture routes are not a substitute for authentication, virus scanning,
content retention, or a camera-quality SLA.

## Failure and boundary table

| Boundary | Observable result | Why it exists |
|---|---|---|
| Empty multipart | `400 Bad Request` | Do not invoke image decoding for an unusable upload. |
| Unsupported multipart content type | `415 Unsupported Media Type` | Reject the declared media type before reading/decoding the body. |
| Encoded bytes exceed the service budget | `413 Payload Too Large` | Protect request memory and I/O before decoding. |
| Unknown/oversized dimensions or pixels | `413 Payload Too Large` | Protect decoder CPU and heap from decompression-bomb-like input. |
| Malformed image after preflight | `400 Bad Request` | A header that looks plausible is not a decodable image. |
| Provider returns no barcode | `200 OK` with `barcodes=[]` | “No code found” is a normal scan result. |
| Provider fails | Provider-neutral `BarcodeException` mapping | Keep the application contract independent of ZXing. |
| Coroutine cancellation | Cancellation is rethrown | A disconnected client must not be converted into a false business failure. |

## Test contracts

| Test | Contract it teaches |
|---|---|
| `BarcodeExtractionServiceTest` format cases | PNG, JPEG, and WebP reach the provider-neutral reader with bounded input. |
| `BarcodeExtractionServiceTest` size/dimension cases | Encoded bytes, decoded sides, and pixel budgets fail before provider work. |
| `BarcodeExtractionServiceTest` malformed/provider cases | Bad images become stable errors; provider errors remain distinguishable from no-result. |
| `BarcodeExtractionServiceTest` cancellation cases | Structured concurrency is preserved rather than swallowed by a broad catch. |
| `BarcodeApiApplicationTest` | Multipart binding, fixture routes, success/no-result, and HTTP error mapping are visible at the wire boundary. |

## What it demonstrates

- `ImmutableImage.extractBarcodes(reader)` with `BarcodeReader` and `BarcodeResult`.
- `immutableExternalImageOf(bytes, ImageDecodeLimits)` as the final bounded decode boundary before
  a provider can inspect pixels. The helper rejects oversized encoded input and unknown or
  oversized dimensions before an unrestricted decoder call.
- A bounded dimension/metadata preflight that preserves the example's stable HTTP `413` response;
  the strict helper remains the final defense after that preflight.
- PNG, JPEG, and WebP decoding, malformed/unknown-dimension rejection, and cancellation-safe
  multipart reads.
- Deterministic `/sample`, `/no-result`, and `/malformed` routes for smoke tests.

## Design and verification

The request path checks upload metadata and dimensions before decoding, then passes the image through
the bounded decoder before calling the injected `BarcodeReader`. This keeps the HTTP 413 response
independent of the ZXing adapter. `BarcodeExtractionServiceTest` covers supported image formats,
size and dimension rejection, malformed input, provider failures, and cancellation;
`BarcodeApiApplicationTest` checks the HTTP contract.

## Run

```bash
./gradlew :image-processing-barcode-api:test
./gradlew :image-processing-barcode-api:bootRun
```

The HTTP surface is `POST /api/barcodes/extract` with a multipart `file` part. The fixture routes
are intentionally deterministic and are for learning and contract tests, not production uploads.
The service maps its own size preflight failures to HTTP `413`, while preserving provider-neutral
`BarcodeException` failures and coroutine cancellation.
