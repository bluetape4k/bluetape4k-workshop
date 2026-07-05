# Ktor DynamoDB Local-First

[한국어](README.ko.md) | English

This module shows a Kotlin-first Ktor REST service backed by DynamoDB while keeping the default
developer workflow local. It uses `DynamoDbKtorPlugin` to create the table against a local AWS
emulator, then exposes a small order-session API with conditional writes, optimistic updates,
bounded scans, readiness checks, and deterministic error responses.

## Architecture

![Ktor DynamoDB architecture](../../docs/images/readme-diagrams/aws-ktor-dynamodb-readme-architecture-01.png)

The route layer owns HTTP shape and JSON error mapping. The service layer owns validation and
version rules. The repository layer owns DynamoDB command budgets and maps conditional write
failures into public workshop errors.

## Request Sequence

![Ktor DynamoDB request sequence](../../docs/images/readme-diagrams/aws-ktor-dynamodb-readme-sequence-01.png)

The sequence keeps the local path fail-closed: local mode requires an explicit emulator endpoint
and dummy credentials. Real AWS mode is opt-in and should be used only with an intentional AWS
profile, IAM policy, and cleanup plan.

## What You Learn

| topic | example |
| --- | --- |
| Ktor plugin runtime | `DynamoDbKtorPlugin` creates the table before routes use the client. |
| Conditional writes | `PutItem` rejects duplicate order-session IDs. |
| Optimistic update | `UpdateItem` checks `expectedVersion` and increments `version`. |
| Error mapping | DynamoDB conditional failures become `409` errors with stable error codes. |
| Bounded listing | `Scan` uses default limit `25`, max limit `100`, and an opaque page token. |
| Local-first boundary | Local mode requires endpoint and credentials, so accidental real AWS calls fail early. |

## API Surface

| method | path | behavior |
| --- | --- | --- |
| `POST` | `/dynamodb/order-sessions` | Creates a session at version `1`. |
| `GET` | `/dynamodb/order-sessions/{id}` | Reads one session or returns `404`. |
| `GET` | `/dynamodb/order-sessions?limit=25&nextToken=...` | Lists a bounded page. |
| `PUT` | `/dynamodb/order-sessions/{id}` | Updates with `expectedVersion`; success increments version. |
| `DELETE` | `/dynamodb/order-sessions/{id}` | Deletes an existing session. |
| `GET` | `/health/readiness` | Confirms that the DynamoDB table is active. |

## Run Locally

The test path starts a bluetape4k AWS emulator through `bluetape4k-testcontainers`.

```bash
./gradlew :aws-ktor-dynamodb:test --max-workers=1
```

To run the Ktor app manually, start a Floci or LocalStack endpoint first, then pass the local
endpoint and dummy credentials explicitly:

```bash
./gradlew :aws-ktor-dynamodb:run \
  -Dbluetape4k.aws.mode=local \
  -Dbluetape4k.aws.emulator=floci \
  -Dbluetape4k.aws.region=ap-northeast-2 \
  -Dbluetape4k.aws.dynamodb.table-name=workshop-order-sessions \
  -Dbluetape4k.aws.dynamodb.endpoint-url=http://localhost:4566 \
  -Dbluetape4k.aws.access-key-id=test \
  -Dbluetape4k.aws.secret-access-key=test
```

```bash
curl -s http://localhost:8080/health/readiness

curl -s -X POST http://localhost:8080/dynamodb/order-sessions \
  -H 'Content-Type: application/json' \
  -d '{"id":"order-1001","customerId":"customer-42","notes":"new order"}'

curl -s http://localhost:8080/dynamodb/order-sessions/order-1001

curl -s -X PUT http://localhost:8080/dynamodb/order-sessions/order-1001 \
  -H 'Content-Type: application/json' \
  -d '{"expectedVersion":1,"status":"APPROVED","notes":"approved"}'

curl -s 'http://localhost:8080/dynamodb/order-sessions?limit=25'
curl -i -X DELETE http://localhost:8080/dynamodb/order-sessions/order-1001
```

## Error Matrix

| condition | status | code |
| --- | ---: | --- |
| Validation failure | `400` | `VALIDATION_FAILED` |
| Malformed JSON | `400` | `MALFORMED_JSON` |
| Oversized body over 64 KiB | `413` | `REQUEST_TOO_LARGE` |
| Bad page token | `400` | `INVALID_PAGE_TOKEN` |
| Duplicate create | `409` | `ORDER_SESSION_EXISTS` |
| Stale update version | `409` | `ORDER_SESSION_VERSION_CONFLICT` |
| Missing read/update/delete target | `404` | `ORDER_SESSION_NOT_FOUND` |
| Readiness table down | `503` | `DYNAMODB_NOT_READY` |
| Unexpected DynamoDB failure | `503` | `DYNAMODB_UNAVAILABLE` |

## Command Budget

| operation | DynamoDB commands |
| --- | --- |
| Create success | One conditional `PutItem`. |
| Read | One `GetItem`. |
| List | One bounded `Scan`; default `25`, max `100`. |
| Update success | One conditional `UpdateItem` with `ReturnValues=ALL_NEW`. |
| Update condition failure | Failed `UpdateItem` plus at most one `GetItem` to classify the conflict. |
| Delete success | One conditional `DeleteItem`. |
| Readiness | One `DescribeTable` with a short timeout. |

## Configuration

| property | default | local mode rule |
| --- | --- | --- |
| `bluetape4k.aws.mode` | `local` | Use `real` only for intentional AWS access. |
| `bluetape4k.aws.emulator` | `floci` | `floci` or `localstack`. |
| `bluetape4k.aws.region` | `ap-northeast-2` | Can be any emulator-supported region. |
| `bluetape4k.aws.dynamodb.table-name` | `workshop-order-sessions` | Auto-created only in local mode. |
| `bluetape4k.aws.dynamodb.endpoint-url` | none | Required in local mode. |
| `bluetape4k.aws.access-key-id` | none | Required in local mode. |
| `bluetape4k.aws.secret-access-key` | none | Required in local mode. |

## Real AWS Opt-In

Real AWS mode does not require an endpoint override and does not auto-create tables:

```bash
./gradlew :aws-ktor-dynamodb:run \
  -Dbluetape4k.aws.mode=real \
  -Dbluetape4k.aws.region=ap-northeast-2 \
  -Dbluetape4k.aws.dynamodb.table-name=workshop-order-sessions
```

Create the table explicitly before running, use a constrained AWS profile, review table costs, and delete the table after the workshop.

## Verification

```bash
./gradlew :aws-ktor-dynamodb:compileKotlin --warning-mode all
./gradlew :aws-ktor-dynamodb:compileTestKotlin --warning-mode all
./gradlew :aws-ktor-dynamodb:test --max-workers=1
```
