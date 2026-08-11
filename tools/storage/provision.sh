#!/usr/bin/env bash
#
# Creates the platform files bucket and everything the services need to use it.
#
# Nothing here exists yet: record-, document- and event-service all point at
# gs://lagu-platform-files, and as of writing that bucket has never been created — the only
# bucket in the project is lagu-invitation-test. Until this runs, those three services cannot
# store or serve a single file in production.
#
# What it sets up:
#   1. The bucket, with uniform bucket-level access (per-object ACLs would undermine the IAM
#      prefix conditions below — a legacy ACL can grant access the condition was meant to deny).
#   2. A CORS policy. Without it, browser PUTs to a presigned URL fail preflight and every
#      upload from the UI breaks, while a server-side PUT works fine — which makes it a
#      confusing thing to debug after the fact.
#   3. One service account per service, each IAM-conditioned to its own key prefix. That
#      scoping is what stops record-service reading document-service's files; this cluster has
#      no Workload Identity, so a prefix-scoped key is the only isolation available.
#   4. The abandoned-upload lifecycle rule, via ./lifecycle.sh.
#
# Usage:
#   tools/storage/provision.sh                 # print what it would do, change nothing
#   APPLY=1 tools/storage/provision.sh         # actually do it
#
#   BUCKET=lagu-platform-files PROJECT=ascendant-might-248610 LOCATION=asia-south1 \
#     APPLY=1 tools/storage/provision.sh
#
# Re-running is safe: every step checks for what it creates first.
#
set -euo pipefail
cd "$(dirname "$0")"

BUCKET="${BUCKET:-lagu-platform-files}"
PROJECT="${PROJECT:-ascendant-might-248610}"
# Same region as the Artifact Registry the cluster already pulls from — cross-region egress on
# every image fetch is a cost nobody notices until the bill.
LOCATION="${LOCATION:-asia-south1}"
APPLY="${APPLY:-0}"

# Origins allowed to PUT directly to the bucket. A presigned URL is useless from a browser
# without its origin here, so this must list every front end that uploads.
CORS_ORIGINS="${CORS_ORIGINS:-https://laguevents.com,https://www.laguevents.com,http://localhost:3000}"

# service:prefix — the key prefix each service owns, matching platform.storage.domain in its
# application.yml. Adding a service means adding it here AND to lifecycle.sh's DOMAINS.
SERVICES="record document event"

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
run() {
  if [ "$APPLY" = "1" ]; then
    echo "+ $*"
    "$@"
  else
    echo "  would run: $*"
  fi
}

command -v gcloud >/dev/null || { echo "gcloud not found" >&2; exit 1; }
gcloud version >/dev/null 2>&1 || {
  echo "gcloud is installed but not working. If it complains about Python, point it at 3.10+:" >&2
  echo "  export CLOUDSDK_PYTHON=/path/to/python3.11" >&2
  exit 1
}

[ "$APPLY" = "1" ] || say "DRY RUN — nothing will be changed. Re-run with APPLY=1."

# ── 1. Bucket ────────────────────────────────────────────────────────────────
say "1. Bucket gs://${BUCKET}"
if gcloud storage buckets describe "gs://${BUCKET}" --project "$PROJECT" >/dev/null 2>&1; then
  echo "  exists — leaving alone"
else
  echo "  does not exist"
  run gcloud storage buckets create "gs://${BUCKET}" \
      --project "$PROJECT" \
      --location "$LOCATION" \
      --uniform-bucket-level-access \
      --public-access-prevention
fi

# ── 2. CORS ──────────────────────────────────────────────────────────────────
say "2. CORS policy"
cors_file="$(mktemp)"; trap 'rm -f "$cors_file"' EXIT
origins_json=$(printf '%s' "$CORS_ORIGINS" | awk -F, '{for(i=1;i<=NF;i++){printf "%s\"%s\"", (i>1?",":""), $i}}')
cat >"$cors_file" <<JSON
[
  {
    "origin": [${origins_json}],
    "method": ["GET", "PUT", "HEAD"],
    "responseHeader": ["Content-Type", "Content-Length", "ETag"],
    "maxAgeSeconds": 3600
  }
]
JSON
# PUT for uploads, GET for signed downloads, HEAD because some clients preflight a range read.
# Content-Type must be allowed as a request header or the signed PUT is rejected at preflight —
# it is bound into the signature, so the browser always sends it.
cat "$cors_file"
run gcloud storage buckets update "gs://${BUCKET}" --cors-file="$cors_file"

# ── 3. Per-service accounts, IAM-conditioned to their own prefix ─────────────
say "3. Service accounts"
mkdir -p ../../../server-scripts/prod/cluster/secrets/files 2>/dev/null || true
key_dir="${KEY_DIR:-../../../server-scripts/prod/cluster/secrets/files}"

for svc in $SERVICES; do
  sa="lagu-${svc}-storage"
  email="${sa}@${PROJECT}.iam.gserviceaccount.com"
  key_file="${key_dir}/gcp-sa-${svc}-service.json"

  echo
  echo "  ${svc}-service → ${email} (prefix ${svc}/)"

  if gcloud iam service-accounts describe "$email" --project "$PROJECT" >/dev/null 2>&1; then
    echo "    account exists"
  else
    run gcloud iam service-accounts create "$sa" \
        --project "$PROJECT" \
        --display-name "lagu ${svc}-service object storage"
  fi

  # objectAdmin, not objectViewer: the service creates, reads, moves (copy+delete on promotion
  # out of pending/) and deletes its own objects. The condition is what keeps that from meaning
  # the whole bucket — resource.name matching the service's own prefix and nothing else.
  run gcloud storage buckets add-iam-policy-binding "gs://${BUCKET}" \
      --member "serviceAccount:${email}" \
      --role roles/storage.objectAdmin \
      --condition="title=${svc}-prefix-only,description=Only objects under ${svc}/,expression=resource.name.startsWith('projects/_/buckets/${BUCKET}/objects/${svc}/')"

  if [ -f "$key_file" ]; then
    echo "    key already present at ${key_file} — not creating another"
    echo "    (every key is a credential that cannot be revoked individually without listing them)"
  else
    run gcloud iam service-accounts keys create "$key_file" \
        --project "$PROJECT" \
        --iam-account "$email"
  fi
done

# ── 4. Lifecycle rule for abandoned uploads ──────────────────────────────────
say "4. Abandoned-upload lifecycle rule"
echo "  delegating to ./lifecycle.sh"
APPLY="$APPLY" ./lifecycle.sh gcs "$BUCKET"

# ── Next steps ───────────────────────────────────────────────────────────────
say "Next"
cat <<'EOF'
  1. Seal the keys into the cluster:
       cd server-scripts/prod/cluster/secrets
       ./seal.sh --only gcp-sa-record
       ./seal.sh --only gcp-sa-document
       ./seal.sh --only gcp-sa-event

  2. Verify the bucket end to end from this repo:
       GCS_SMOKE_BUCKET=<bucket> GCS_SMOKE_CREDENTIALS=<a key file> \
         ./gradlew :libs:storage:test --tests '*GcsStorageSmokeIT*'

     It writes and deletes under a smoke-test/ prefix and asserts it leaves nothing behind.
     Note it will fail the IAM condition with a per-service key — use an unconditioned
     credential for the smoke test, or point it at a scratch bucket.

  3. CORS is the one thing that test cannot prove: it PUTs from a Java client, which does not
     preflight. Confirm a real browser upload before calling this done.
EOF
