# S3 Vectors + Access Grants Workshop

[한국어](README.ko.md) | English

This example teaches how to keep Amazon S3 Vectors search separate from S3
Access Grants object authorization. The default profile is local-first: it
builds the same bluetape4k AWS facade requests the production path would use,
but it does not create real AWS clients, does not require credentials, and never
returns temporary credential fields in API responses.

## Architecture

![S3 Vectors and Access Grants architecture](../../docs/images/readme-diagrams/aws-s3-vectors-access-grants-readme-architecture-01.png)

The application layer owns the learning policy. `S3VectorsAccessService` writes
and queries document vectors through `S3VectorsOperations`, ranks the local demo
documents deterministically, then asks `S3AccessGrantsOperations` for read access
only after a top match is selected.

## Request Flow

![S3 Vectors and Access Grants sequence](../../docs/images/readme-diagrams/aws-s3-vectors-access-grants-readme-sequence-01.png)

The `alt requireAccessGrant=true` area is intentionally transparent and shows
the optional boundary. A caller can request vector ranking only, or it can ask
the service to gate the selected object URI through Access Grants.

## What You Learn

| Topic | Workshop behavior |
| --- | --- |
| Vector write boundary | Document upsert calls `PutVectorsRequest` through bluetape4k `S3VectorsOperations`. |
| Vector query boundary | Search calls `QueryVectorsRequest`, then ranks deterministic local vectors for repeatable tests. |
| Access Grants boundary | Object access calls `GetDataAccessRequest` with `READ` permission only after a top match exists. |
| Redaction | API reports expose `redacted=true`; no temporary access key, secret key, or session token fields are serialized. |
| Surface separation | Ordinary S3 storage, pre-signed URLs, S3 Vectors, and Access Grants remain separate examples and boundaries. |
| Local safety | Default tests and smoke runs need no AWS account, no AWS credentials, and no live S3 buckets. |

## Run Locally

```bash
./gradlew :aws-s3-vectors-access-grants:test
./gradlew :aws-s3-vectors-access-grants:bootRun
```

Upsert a local demo document:

```bash
curl -s http://localhost:8080/aws/s3-vectors/documents \
  -H 'Content-Type: application/json' \
  -d '{
    "documentId": "doc-100",
    "title": "Coroutine Flow backpressure",
    "objectKey": "docs/coroutines-flow.md",
    "vector": [0.9, 0.1, 0.0],
    "metadata": { "topic": "coroutines" }
  }' | jq
```

Search and require an Access Grants decision for the selected object:

```bash
curl -s http://localhost:8080/aws/s3-vectors/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": [0.8, 0.2, 0.0],
    "topK": 1,
    "requireAccessGrant": true
  }' | jq
```

Expected local shape:

```json
{
  "query": { "state": "PUBLISHED", "boundary": "S3 Vectors", "message": "" },
  "matches": [
    {
      "documentId": "doc-100",
      "title": "Coroutine Flow backpressure",
      "objectUri": "s3://workshop-documents/docs/coroutines-flow.md",
      "score": 0.9909924304103231,
      "metadata": { "topic": "coroutines" }
    }
  ],
  "access": {
    "state": "GRANTED",
    "target": "s3://workshop-documents/docs/coroutines-flow.md",
    "permission": "READ",
    "redacted": true,
    "message": "Temporary data access approved; sensitive AWS values are intentionally omitted."
  }
}
```

Set `requireAccessGrant=false` when you only want vector ranking:

```json
{
  "query": [0.8, 0.2, 0.0],
  "topK": 1,
  "requireAccessGrant": false
}
```

## Configuration

Default `src/main/resources/application.yml` keeps ordinary S3
auto-configuration disabled so the sample cannot resolve a region or credentials
from the host by accident:

```yaml
bluetape4k:
  aws:
    s3:
      enabled: false
workshop:
  aws:
    s3-vector-access:
      vector-bucket-name: semantic-documents
      index-name: docs-rag
      document-bucket-name: workshop-documents
      object-prefix: docs/
      max-search-results: 5
      max-vector-dimensions: 16
      access-grants:
        account-id: "123456789012"
        location-arn: arn:aws:s3:ap-northeast-2:123456789012:access-grants/default/location/default
```

## Optional Real AWS Profile

Real AWS calls are intentionally outside the default path. Use them only in a
manual environment where cost, cleanup, IAM permissions, region selection, S3
Vectors index setup, and Access Grants instance setup are understood.

```bash
export AWS_REGION=ap-northeast-2
export AWS_PROFILE=your-profile

./gradlew :aws-s3-vectors-access-grants:bootRun \
  --args='--spring.profiles.active=real-aws \
  --bluetape4k.aws.s3.enabled=true \
  --bluetape4k.aws.s3.region=ap-northeast-2 \
  --bluetape4k.aws.s3-vectors.enabled=true \
  --bluetape4k.aws.s3-vectors.region=ap-northeast-2 \
  --bluetape4k.aws.s3.access-grants.enabled=true \
  --bluetape4k.aws.s3.access-grants.region=ap-northeast-2'
```

The example still redacts the Access Grants data-access response. Inspect
temporary AWS values only in a separate, controlled manual debugging path.

## Test Coverage

```bash
./gradlew :aws-s3-vectors-access-grants:compileKotlin
./gradlew :aws-s3-vectors-access-grants:compileTestKotlin
./gradlew :aws-s3-vectors-access-grants:test
```

The tests verify vector upsert/query request boundaries, local ranking, Access
Grants gating, redacted response shape, failure isolation, cancellation
propagation, and MVC JSON output without real AWS credentials.
