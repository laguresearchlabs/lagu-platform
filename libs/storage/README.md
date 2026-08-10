# libs/storage

Presigned object storage for GCS and S3. **File bytes never pass through a platform JVM** —
services mint short-lived presigned URLs and the client transfers directly to and from the
bucket.

Used by `record-service` and `document-service`. Replaced the standalone `image-service`.

## Using it

Add the dependency and put `com.lagu.platform.storage` on the service's component scan, the same
way `com.lagu.platform.security` is wired:

```kotlin
implementation(project(":libs:storage"))
```

```java
@SpringBootApplication(scanBasePackages = {
        "com.lagu.platform.myservice",
        "com.lagu.platform.common",
        "com.lagu.platform.security",
        "com.lagu.platform.storage"
})
```

```yaml
platform:
  storage:
    domain: myservice          # key prefix this service owns
    provider: ${STORAGE_PROVIDER:gcs}
    upload-url-ttl: 15m
    download-url-ttl: 10m
    gcs:
      bucket: ${GCS_BUCKET:}
      service-account-email: ${GCS_SIGNER_SA:}
```

## The upload flow

1. `StorageKeys.buildPending(...)` + `presignUpload(key, contentType, ttl)` — hand the client a
   PUT URL. `contentType` is bound into the signature, so the bucket rejects a PUT that sends
   anything else.
2. The client PUTs the bytes.
3. `MediaIngest.confirm(...)` — verify, scan, measure, promote, derive. *Then* persist the key
   it returns.

Step 3 is not optional. Everything the client said in step 1 is a claim; only the bucket's own
measurement and the object's real bytes establish anything. Under the old multipart flow the
service saw the payload and could sniff it inline — with direct-to-bucket uploads, this is the
only place that check can happen.

Don't reimplement step 3. `MediaIngest` is that sequence, and the order within it is
load-bearing: nothing reaches a durable key until every check has passed, the object is read
into memory exactly once for the scanner/measurement/thumbnailer, and anything rejected is
deleted rather than left in the bucket — an orphan there is still readable to whoever holds the
signed URL from step 1.

```java
MediaIngest.Result media = mediaIngest.confirm(MediaIngest.Request.builder()
        .pendingKey(clientSuppliedKey)      // must be under pending/
        .policy(policy)                     // formats + size cap
        .image(constraints)                 // pixel bounds, or ImageConstraints.NONE
        .derivatives(true)                  // thumbnails; false for documents
        .build());
// media.key() is the durable key — the pending one no longer exists.
```

## Pending keys, and why abandoned uploads disappear

Uploads land under `{domain}/pending/{ownerId}/…` and only move to `{domain}/{ownerId}/…` once
verified. An upload URL can be minted, used, and never confirmed — the tab closes, the request
fails — leaving a real, unreferenced, permanent object that no application code can reliably
clean up, because the service that would notice is the one that never got the confirm call.

Under the pending prefix "old" means "abandoned", so the bucket sweeps them itself:
`tools/storage/lifecycle.sh gcs <bucket>` prints the rule, `APPLY=1` applies it.

The `pending` segment sits **immediately after the domain** and that position is the whole
point — lifecycle conditions on GCS and S3 match a prefix of the full object name, so
`record/pending/` is targetable where a segment further down the key would not be. Keeping the
domain first also leaves the per-service IAM binding covering pending and durable objects alike.

Because a key has two shapes either side of confirm, use `StorageKeys.isOwnedBy(key, domain,
ownerId)` for ownership rather than open-coding `startsWith` — a call site that checks only one
shape is how one of them ends up unguarded.

## Malware scanning

`MediaIngest` scans every confirmed object before it is persisted, via `MediaScanner`. Byte
signatures answer "are these bytes the format they claim to be", which is a different question
from "are these bytes safe" — a valid PDF header can still carry a payload, and vendor documents
get opened by staff.

```yaml
platform:
  storage:
    scanner:
      enabled: ${CLAMAV_ENABLED:false}
      host: ${CLAMAV_HOST:clamav}
```

Off by default so local runs and tests need no daemon; `docker-compose.yml` runs clamd and turns
it on. **It fails closed**: an unreachable clamd fails uploads rather than passing them
unscanned, and an unrecognised reply is a failure, not a pass. That is a real availability
coupling — `enabled: false` is the supported way to switch it off, not a timeout.

## Derivatives

`ImageProcessor` is the one place image bytes deliberately transit a JVM. Neither a dimension
check nor a thumbnail can come from a ranged read, and the alternative — an image proxy in front
of the bucket — trades a dependency here for a component to deploy and secure on on-prem k3s.

Memory is the risk and it is bounded twice: dimensions are read from the header without decoding
pixels, and scaling subsamples inside the decoder so a 50-megapixel photo never becomes a 200MB
raster on its way to a 640px thumbnail.

Derivatives are **best-effort**. A format with no decoder — HEIC, AVIF, video, PDF — leaves the
original perfectly usable, so the upload succeeds with `variantKeys()` empty. Failing an upload
because a thumbnail could not be produced would trade a cosmetic problem for a functional one.
ImageIO ships no WebP reader, hence the TwelveMonkeys plugin on the classpath.

**Store keys, never URLs.** Sign a download URL per request with `presignDownload`. The
predecessor to this library returned a 10-minute signed URL that callers persisted as if it were
durable, so every stored file reference expired minutes after upload.

**Validate the key's prefix on read as well as on write.** A stored key usually lives somewhere
a caller can also reach — record-service keeps it in the record's JSONB, which the generic record
write touches too. Checking the prefix only at confirm time leaves signing driven by a value that
could have been written by another route.

## What an upload is allowed to be

`MediaPolicy` holds the allowed content types and the size cap, and is the only place either is
enforced. Both checks matter and they are not interchangeable:

```java
// step 1 — the client's declarations, before a URL is minted. Cheap; proves nothing.
policy.checkDeclared(fileName, contentType, declaredSizeBytes);

// step 3 — the bucket's measurement and the object's real leading bytes. This is the one
// that establishes anything. On failure, delete the object: it is already in the bucket.
policy.checkStored(meta.contentType(), meta.sizeBytes(),
                   storage.readRange(key, ContentTypeSniffer.HEADER_BYTES));
```

Use `meta.contentType()`, not the client's declaration, as the type to check and sniff against —
it is what the object will be *served* as, so it is what a browser acts on. It is also
trustworthy, because `presignUpload` binds Content-Type into the signature.

### Configuring it without a deploy

Services declare a built-in default and let admin configuration override it:

```java
MediaPolicy policy = DEFAULT.overriddenBy(allowedMimeTypes, maxSizeMb);
```

Nulls and empties leave that half of the default standing, so an unconfigured slot is still a
guarded one. Where the configuration comes from:

| Upload slot | Configured in | Carried by |
|---|---|---|
| KYC / vendor documents | schema-registry → document requirement | `allowed_mime_types`, `max_size_mb` |
| Record FILE/IMAGE fields | schema-registry → field definition | `validation_rules.allowedMimeTypes`, `.maxSizeMb` |

Both were already editable and persisted before this library enforced them — document-service
used private constants and never read the columns, so the admin screen saved and changed nothing.

**`ContentTypeSniffer` is the ceiling on what can be configured.** It fails closed, so a content
type it has no signature for rejects every upload rather than allowing them. Adding a format is
therefore the one media change that still needs a deploy — one entry in that class's table.
`MediaPolicy.unverifiableTypes()` reports configured types that fall outside it, and
`DocumentTypeRegistry` logs them when configuration loads, so the mismatch surfaces at startup
rather than as vendors mysteriously failing to upload.

## Keys and credentials

`StorageKeys.build` produces `{domain}/{ownerId}/{uuid}_{filename}`. The prefix exists so each
service's bucket IAM binding can be scoped to its own `domain/`, which is what replaces the
single-pod credential boundary that image-service used to provide. Filenames are sanitised, so a
traversal attempt in the name cannot climb out of the prefix (`StorageKeysTest` covers this).

Production runs on **on-prem k3s**, where GCP Workload Identity is not available — so a mounted
key file *is* the service's identity. Each service therefore gets its **own** key
(`lagu-gcp-sa-record`, `lagu-gcp-sa-document`), granted `roles/storage.objectAdmin` under an IAM
condition restricting it to its own prefix. A single shared key would make the prefixes a naming
convention rather than a boundary. See `server-scripts/prod/cluster/secrets/seal.sh` for the
`gcloud` invocations, and note the bucket needs a **CORS policy** or browser PUTs to a presigned
URL fail preflight.

With a key file, V4 signing happens locally and `service-account-email` is unused. It is only
needed on a keyless setup (GKE Workload Identity, or ADC generally), where signing routes through
IAM `signBlob` and the impersonated account needs `roles/iam.serviceAccountTokenCreator` on
itself. Both paths are implemented; the code picks based on the credential type it resolves.

## Testing and local development

Set `platform.storage.provider` to anything other than `gcs`/`s3` (e.g. `none`) so neither
backend configuration activates and no credential resolution is attempted, then supply a mocked
`StorageService`. See `DocumentServiceIntegrationTest`.

The same applies to `bootRun`: the default provider is `gcs`, so a service starting without
Application Default Credentials available will fail at startup. That is deliberate — a service
that needs storage should not start pretending it has it. If you are working on something
unrelated to file upload, run with `STORAGE_PROVIDER=none`.
