#!/usr/bin/env bash
#
# Applies the bucket lifecycle rule that sweeps abandoned uploads.
#
# An upload URL can be minted, used, and then never confirmed — the vendor closes the tab, the
# request fails, the app crashes. The object is real, unreferenced, and permanent, and no amount
# of application code can clean it up reliably: the service that would notice is the one that
# never received the confirm call.
#
# So uploads land under `{domain}/pending/` and only move to their durable key once they have
# been verified (StorageKeys.buildPending / promote). Under that prefix "old" means "abandoned",
# and the bucket can delete them on its own. Anything past confirm lives outside the prefix and
# this rule cannot reach it.
#
# The `pending` segment sits immediately after the domain for exactly this reason: lifecycle
# conditions on both GCS and S3 match a prefix of the whole object name, so `record/pending/`
# is targetable where a segment further down the key would not be.
#
# One day is comfortably longer than the 15-minute upload-URL TTL, so a slow but legitimate
# upload is never at risk.
#
# Usage:
#   tools/storage/lifecycle.sh gcs my-bucket
#   tools/storage/lifecycle.sh s3  my-bucket
#   PENDING_RETENTION_DAYS=3 tools/storage/lifecycle.sh gcs my-bucket
#   APPLY=1 tools/storage/lifecycle.sh gcs my-bucket     # actually apply it
#
# Without APPLY=1 it prints the policy and the command, and changes nothing.
#
set -euo pipefail

PROVIDER="${1:-}"
BUCKET="${2:-}"
DAYS="${PENDING_RETENTION_DAYS:-1}"
APPLY="${APPLY:-0}"

# Every service that owns a key prefix in this bucket. A service missing from this list keeps
# its abandoned uploads forever, silently — add one here when a new service starts using storage.
DOMAINS=(record document)

if [[ -z "$PROVIDER" || -z "$BUCKET" ]]; then
    echo "usage: $0 <gcs|s3> <bucket>" >&2
    exit 2
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

case "$PROVIDER" in
gcs)
    prefixes=""
    for domain in "${DOMAINS[@]}"; do
        [[ -n "$prefixes" ]] && prefixes+=", "
        prefixes+="\"${domain}/pending/\""
    done

    cat >"$tmp" <<JSON
{
  "lifecycle": {
    "rule": [
      {
        "action": { "type": "Delete" },
        "condition": {
          "age": ${DAYS},
          "matchesPrefix": [${prefixes}]
        }
      }
    ]
  }
}
JSON
    cat "$tmp"
    cmd=(gcloud storage buckets update "gs://${BUCKET}" "--lifecycle-file=${tmp}")
    ;;
s3)
    # S3 allows one prefix per rule, so this is one rule per domain rather than one rule with
    # a list — the same policy expressed the way S3 accepts it.
    rules=""
    for domain in "${DOMAINS[@]}"; do
        [[ -n "$rules" ]] && rules+=","
        rules+="
    {
      \"ID\": \"expire-abandoned-${domain}-uploads\",
      \"Status\": \"Enabled\",
      \"Filter\": { \"Prefix\": \"${domain}/pending/\" },
      \"Expiration\": { \"Days\": ${DAYS} }
    }"
    done

    cat >"$tmp" <<JSON
{
  "Rules": [${rules}
  ]
}
JSON
    cat "$tmp"
    cmd=(aws s3api put-bucket-lifecycle-configuration --bucket "${BUCKET}"
         --lifecycle-configuration "file://${tmp}")
    ;;
*)
    echo "unknown provider: $PROVIDER (expected gcs or s3)" >&2
    exit 2
    ;;
esac

echo
if [[ "$APPLY" == "1" ]]; then
    echo "Applying to ${BUCKET}..."
    "${cmd[@]}"
    echo "Done. Abandoned uploads are now deleted after ${DAYS} day(s)."
else
    # This replaces the bucket's whole lifecycle configuration on both providers, so it prints
    # by default rather than applying — an existing rule you did not know about would be gone.
    echo "Dry run. Re-run with APPLY=1 to apply:"
    printf '  %q' "${cmd[@]}"; echo
    echo
    echo "NOTE: this replaces the bucket's entire lifecycle configuration. Check for existing"
    echo "rules first:  gcloud storage buckets describe gs://${BUCKET} --format='value(lifecycle)'"
fi
