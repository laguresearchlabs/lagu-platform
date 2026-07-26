# event-nest → event-service migration

One-off script to migrate event-nest's data (event-core's Postgres `events`/`event_members`/
`event_join_requests`/`addresses`, plus birthday-service/wedding-service's MongoDB
`birthday_details`/`wedding_details`) into lagu-platform's `apps/event-service` + `record-service`.

Not part of the Gradle build. Run it once, by hand, during the actual cutover.

## Before running

1. Read the module docstring at the top of `migrate_events.py` — it documents exactly what is
   and isn't migrated (vendor links, old free-text venue/special-request fields, and historical
   audit trail are explicitly out of scope, by design — see the file for why).
2. This has **not been run against real data** — there was no way to stand up event-nest's
   Postgres/MongoDB or a live event-service instance in the environment this was written in
   (verified: no Docker daemon reachable, no `pip` available to even import-check
   `psycopg2`/`pymongo`/`requests`). It was syntax-checked
   (`python3 -m py_compile migrate_events.py`) and every field name/column mapping was
   cross-referenced directly against event-nest's entity source files
   (`EventEntity`/`AddressEntity`/`EventMemberEntity`/`EventJoinRequestEntity`/
   `BirthdayDetailsEntity`/`WeddingDetailsEntity`), but treat it as reviewed-not-battle-tested.
   **Run `--dry-run` first, then `--limit 5` against a staging copy of the data, and manually
   verify a handful of migrated events before a full run.**
3. Make sure the target `event-service` instance is reachable directly (not through the
   gateway — this script authenticates as an internal service, the same way vendor-service's
   `RecordServiceClient` does, which requires `X-Internal-Service` + the shared gateway secret;
   the gateway would strip those headers if the request came from outside).

## Setup

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

## Environment

| Variable | Example | Notes |
|---|---|---|
| `EVENTNEST_PG_DSN` | `postgresql://user:pass@host:5432/event_manager` | event-core's database |
| `EVENTNEST_MONGO_URI` | `mongodb://user:pass@host:27017` | birthday-service/wedding-service's database |
| `EVENT_SERVICE_BASE_URL` | `http://localhost:8110` | direct to event-service, default shown |
| `PLATFORM_GATEWAY_SHARED_SECRET` | (matches event-service's config) | not required for `--dry-run` |

## Running

```bash
python migrate_events.py --dry-run              # prints every planned API call, no writes
python migrate_events.py --dry-run --limit 5     # same, just the first 5 eligible events
python migrate_events.py --limit 5               # real run, first 5 events only — do this before a full run
python migrate_events.py                         # full run
```

Output: a per-event log to stdout, plus `manual_review_report.json` (written to the current
directory) listing every event that needs a human follow-up — unmapped fields, vendor links
that need re-linking once vendor data has its own migration, multiple-address events, etc.

## What this does NOT do

- Doesn't touch vendor-management's vendor data or attempt to re-link `partyHallIds`/
  `photographerIds`/`catererIds`/`decoratorIds` to real lagu-platform vendor records — that
  requires vendor data to have its own migration first (unstarted, per
  `project_lagu_platform_review.md`). Old vendor UUIDs are captured in the report so nothing is
  silently lost.
- Doesn't migrate event-nest's posts-service data (social feed) — see the separate
  `EVENT_POST`/`EVENT_COMMENT` rebuild task; there's nothing to migrate into yet.
- Doesn't migrate notifications or the old `event_audit` trail — record-service's own audit
  trail (`GET /api/v1/records/{id}/history`) starts fresh from the migrated state forward.
- Doesn't decommission anything in event-nest itself — this only writes to the new platform.
