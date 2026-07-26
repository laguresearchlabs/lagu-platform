#!/usr/bin/env python3
"""
One-off migration: event-nest (event-core Postgres + birthday-service/wedding-service MongoDB)
-> lagu-platform's apps/event-service + record-service.

This is NOT part of the Gradle build and is not run automatically. It talks to the new
event-service over HTTP using the same internal-service trust mechanism every other
lagu-platform service uses (X-Internal-Service + X-Platform-Gateway-Secret), so it exercises
the exact same validation/authorization code path a real client would -- it does not write to
any lagu-platform database directly.

Scope and known gaps (read before running):
  - Only events whose event_type is "Birthday" or "Wedding" are migrated -- those are the only
    two object types that exist in schema-registry today (see SchemaRegistrySeeder.java). Events
    of the other four seeded types (Anniversary/Baby Shower/Graduation/Party) are skipped and
    logged -- there is no equivalent BIRTHDAY_EVENT-style schema for them yet.
  - Events with status=DELETED are skipped.
  - Only the first address row per event is migrated (the new schema has one "address" field
    group per event, event-core's schema allows multiple).
  - partyHallIds/photographerIds/catererIds/decoratorIds (raw UUID lists) are NOT migrated here
    -- they should become RecordRelationship links to real VENUE/PHOTOGRAPHER/CATERER/DECORATOR
    records via POST /api/v1/events/{id}/vendors, which requires resolving each old vendor UUID
    to whatever record it corresponds to on the new platform. That mapping doesn't exist yet
    (vendor-management -> lagu-platform vendor data migration is a separate, unstarted project
    per project_lagu_platform_review.md) -- out of scope here. The old IDs are captured in the
    per-event "manual_review" report so nothing is silently lost.
  - Old wedding_details.venueName (free text) and specialRequests have no equivalent field in
    the new schema (venue is a real relationship now, not a text field) -- captured in the
    manual_review report per event, not migrated into `data`.
  - Old EventStatus (ACTIVE/CANCELLED/COMPLETED/DELETED) is coarser than the new workflow
    (PLANNING/CONFIRMED/IN_PROGRESS/COMPLETED/CANCELLED/ARCHIVED). This script drives the new
    event through the same transition endpoints a real user would use (POST .../transition),
    with a best-effort mapping documented in STATUS_TRANSITIONS below -- it does not fabricate
    workflow history, and old ACTIVE events land on CONFIRMED, not IN_PROGRESS/PLANNING.
  - Notifications and the old audit trail (event_audit) are NOT migrated -- record-service has
    its own real audit trail (GET /api/v1/records/{id}/history) going forward; there is no
    equivalent for pre-migration history, which is an accepted, permanent gap.
  - Member invites created here land as event-service's normal EventMember rows; a historically
    ACCEPTED member is invited then immediately accepted on their behalf (POST .../me/accept
    called with that member's own X-User-Id, which the internal-service trust path allows) so
    the resulting state matches what invite+accept would have produced live.

Usage:
    pip install psycopg2-binary pymongo requests
    export EVENTNEST_PG_DSN="postgresql://user:pass@host:5432/event_manager"
    export EVENTNEST_MONGO_URI="mongodb://user:pass@host:27017"
    export EVENT_SERVICE_BASE_URL="http://localhost:8110"   # direct to the service, not the gateway
    export PLATFORM_GATEWAY_SHARED_SECRET="..."             # must match event-service's config
    python migrate_events.py --dry-run                      # prints what would happen, no writes
    python migrate_events.py                                # real run
    python migrate_events.py --limit 5                      # sanity-check a handful first

Output: prints a per-event migration log to stdout, and writes manual_review_report.json in the
current directory listing every unmapped/lossy field encountered, keyed by old event id.
"""

import argparse
import json
import os
import sys
import time
import uuid

import psycopg2
import psycopg2.extras
import pymongo
import requests

EVENTNEST_PG_DSN = os.environ.get("EVENTNEST_PG_DSN")
EVENTNEST_MONGO_URI = os.environ.get("EVENTNEST_MONGO_URI")
EVENT_SERVICE_BASE_URL = os.environ.get("EVENT_SERVICE_BASE_URL", "http://localhost:8110")
GATEWAY_SECRET = os.environ.get("PLATFORM_GATEWAY_SHARED_SECRET")

MIGRATION_TOOL_SERVICE_NAME = "migration-tool"

# Old EventStatus -> sequence of workflow triggers to fire after creation (event starts in
# PLANNING). See workflow-service's event_lifecycle_birthday/event_lifecycle_wedding.
STATUS_TRANSITIONS = {
    "ACTIVE": ["confirm"],
    "COMPLETED": ["confirm", "complete"],
    "CANCELLED": ["cancel"],
    # DELETED events are filtered out before this map is consulted.
}

VISIBILITY_MAP = {"PUBLIC": "PUBLIC", "PRIVATE": "PRIVATE"}

# Free-text virtualPlatform -> new enum, case-insensitive substring match, else OTHER.
VIRTUAL_PLATFORM_MAP = [
    ("zoom", "ZOOM"),
    ("meet", "GOOGLE_MEET"),
    ("teams", "MICROSOFT_TEAMS"),
]


def resolve_virtual_platform(old_value):
    if not old_value:
        return None
    lowered = old_value.lower()
    for needle, mapped in VIRTUAL_PLATFORM_MAP:
        if needle in lowered:
            return mapped
    return "OTHER"


def iso(dt):
    return dt.isoformat() if dt else None


class EventServiceClient:
    """Talks to event-service using the same X-Internal-Service trust path RecordServiceClient
    uses elsewhere in lagu-platform -- see libs/security/GatewayHeaderFilter.buildServiceContext.
    X-User-Id is set per-request to whichever historical user is "acting", exactly like a real
    gateway-authenticated request would carry the caller's identity."""

    def __init__(self, base_url, secret, dry_run):
        self.base_url = base_url.rstrip("/")
        self.secret = secret
        self.dry_run = dry_run
        self.session = requests.Session()

    def _headers(self, acting_user_id):
        return {
            "X-Internal-Service": MIGRATION_TOOL_SERVICE_NAME,
            "X-Platform-Gateway-Secret": self.secret,
            "X-User-Id": str(acting_user_id),
            "Content-Type": "application/json",
        }

    def _call(self, method, path, acting_user_id, body=None):
        if self.dry_run:
            print(f"    [dry-run] {method} {path} as {acting_user_id} body={json.dumps(body) if body else None}")
            return {"data": {"id": str(uuid.uuid4())}}  # fake id so downstream steps can be traced
        resp = self.session.request(method, f"{self.base_url}{path}",
                                     headers=self._headers(acting_user_id), json=body, timeout=30)
        if not resp.ok:
            raise RuntimeError(f"{method} {path} failed: {resp.status_code} {resp.text}")
        return resp.json() if resp.content else {}

    def create_event(self, owner_user_id, object_type, data):
        result = self._call("POST", "/api/v1/events", owner_user_id,
                             {"objectType": object_type, "data": data})
        return result["data"]["id"]

    def transition(self, event_id, owner_user_id, trigger):
        self._call("POST", f"/api/v1/events/{event_id}/transition", owner_user_id, {"trigger": trigger})

    def invite_member(self, event_id, owner_user_id, member_user_id, role):
        self._call("POST", f"/api/v1/events/{event_id}/members", owner_user_id,
                    {"userId": str(member_user_id), "role": role})

    def respond_to_invite(self, event_id, member_user_id, accept):
        path = f"/api/v1/events/{event_id}/members/me/{'accept' if accept else 'decline'}"
        self._call("PATCH", path, member_user_id)

    def create_join_request(self, event_id, requester_user_id, requested_role, message):
        self._call("POST", f"/api/v1/events/{event_id}/join-requests", requester_user_id,
                    {"requestedRole": requested_role, "message": message})


def fetch_events(pg_conn, limit):
    cur = pg_conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    cur.execute("""
        SELECT e.*, et.name AS event_type_name
        FROM events e
        JOIN event_types et ON et.id = e.event_type_id
        WHERE et.name IN ('Birthday', 'Wedding')
        ORDER BY e.created_timestamp
        %s
    """ % (f"LIMIT {int(limit)}" if limit else ""))
    return cur.fetchall()


def fetch_address(pg_conn, event_id):
    cur = pg_conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    cur.execute("SELECT * FROM addresses WHERE event_id = %s ORDER BY created_timestamp LIMIT 2", (event_id,))
    rows = cur.fetchall()
    return rows[0] if rows else None, len(rows) > 1


def fetch_members(pg_conn, event_id):
    cur = pg_conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    cur.execute("SELECT * FROM event_members WHERE event_id = %s", (event_id,))
    return cur.fetchall()


def fetch_join_requests(pg_conn, event_id):
    cur = pg_conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    cur.execute("SELECT * FROM event_join_requests WHERE event_id = %s AND status = 'PENDING'", (event_id,))
    return cur.fetchall()


# partyHallIds/photographerIds/catererIds/decoratorIds are @ElementCollection fields, stored in
# their own join tables (event_party_hall_ids etc.), not columns on the `events` row itself.
VENDOR_LINK_TABLES = [
    ("event_party_hall_ids", "party_hall_id"),
    ("event_photographer_ids", "photographer_id"),
    ("event_caterer_ids", "caterer_id"),
    ("event_decorator_ids", "decorator_id"),
]


def fetch_vendor_link_ids(pg_conn, event_id):
    cur = pg_conn.cursor()
    ids = []
    for table, column in VENDOR_LINK_TABLES:
        cur.execute(f"SELECT {column} FROM {table} WHERE event_id = %s", (event_id,))
        ids.extend(str(row[0]) for row in cur.fetchall())
    return ids


def build_base_data(event, address):
    data = {
        "name": event["title"],
        "description": event.get("description"),
        "start_datetime": iso(event["start_date_time"]),
        "end_datetime": iso(event["end_date_time"]),
        "timezone": event.get("timezone"),
        "visibility": VISIBILITY_MAP.get(event.get("visibility"), "PRIVATE"),
        "is_virtual": bool(event.get("is_virtual")),
        "virtual_meeting_url": event.get("virtual_meeting_url"),
        "virtual_meeting_provider": resolve_virtual_platform(event.get("virtual_platform")),
        "cover_image": event.get("cover_image_url"),
    }
    if address:
        data.update({
            "address_line1": address.get("line1"),
            "address_line2": address.get("line2"),
            "city": address.get("city"),
            "state": address.get("state"),
            "postal_code": address.get("postal_code"),
            "latitude": float(address["latitude"]) if address.get("latitude") is not None else None,
            "longitude": float(address["longitude"]) if address.get("longitude") is not None else None,
        })
    return {k: v for k, v in data.items() if v is not None}


def build_birthday_data(base, mongo_doc, manual_review):
    data = dict(base)
    if not mongo_doc:
        manual_review.append("no birthday_details document found in MongoDB")
        return data
    data.update({k: v for k, v in {
        "birthday_person_name": mongo_doc.get("birthdayPersonName"),
        "birthday_person_age": mongo_doc.get("age"),
        "birthday_person_gender": mongo_doc.get("gender"),
        "event_theme": mongo_doc.get("theme"),
        "dresscode": mongo_doc.get("dresscode"),
        "cake_preference": mongo_doc.get("cakePreference"),
        "menu_preference": mongo_doc.get("menuPreference"),
        "gift_preference": mongo_doc.get("giftPreference"),
        "party_style": mongo_doc.get("partyStyle"),
        "guest_count_adults": mongo_doc.get("adultCount"),
        "guest_count_children": mongo_doc.get("childCount"),
        "rsvp_deadline": mongo_doc.get("rsvpDeadline"),
        "surprise_mode": mongo_doc.get("surpriseMode"),
    }.items() if v is not None})

    if mongo_doc.get("wishList"):
        data["wish_list"] = [
            {"item": w.get("item"), "priority": w.get("priority"),
             "purchased": w.get("purchased"), "purchasedBy": w.get("purchasedBy")}
            for w in mongo_doc["wishList"]
        ]
    if mongo_doc.get("tasks"):
        data["planning_tasks"] = [
            {"title": t.get("title"), "assignee": t.get("assignee"),
             "dueDate": t.get("dueDate"), "status": t.get("status")}
            for t in mongo_doc["tasks"]
        ]
    if mongo_doc.get("budgetItems"):
        data["budget_items"] = [
            {"category": b.get("category"), "description": b.get("description"),
             "estimatedCost": b.get("estimatedCost"), "actualCost": b.get("actualCost")}
            for b in mongo_doc["budgetItems"]
        ]
    if mongo_doc.get("schedule"):
        data["schedule_activities"] = [
            {"time": s.get("time"), "activity": s.get("activity"), "durationMinutes": s.get("durationMinutes")}
            for s in mongo_doc["schedule"]
        ]
    if mongo_doc.get("specialRequests"):
        manual_review.append(f"birthday specialRequests not migrated (no field for it): {mongo_doc['specialRequests']!r}")
    return data


def build_wedding_data(base, mongo_doc, manual_review):
    data = dict(base)
    if not mongo_doc:
        manual_review.append("no wedding_details document found in MongoDB")
        return data
    data.update({k: v for k, v in {
        "bride_name": mongo_doc.get("brideName"),
        "groom_name": mongo_doc.get("groomName"),
        "ceremony_type": mongo_doc.get("ceremonyType"),
        "reception_style": mongo_doc.get("receptionStyle"),
        "event_theme": mongo_doc.get("weddingTheme"),
        "dresscode": mongo_doc.get("dresscode"),
        "menu_preference": mongo_doc.get("menuPreference"),
        "expected_guests": mongo_doc.get("guestCount"),
        "rsvp_deadline": mongo_doc.get("rsvpDeadline"),
        "wedding_website_url": mongo_doc.get("weddingWebsiteUrl"),
    }.items() if v is not None})
    if mongo_doc.get("venueName"):
        manual_review.append(f"venueName not migrated (venue is now a VENUE record relationship, "
                              f"not free text): {mongo_doc['venueName']!r}")
    if mongo_doc.get("specialRequests"):
        manual_review.append(f"wedding specialRequests not migrated (no field for it): {mongo_doc['specialRequests']!r}")
    return data


def migrate_event(pg_conn, mongo_db, client, event, report):
    old_id = event["id"]
    object_type = "BIRTHDAY_EVENT" if event["event_type_name"] == "Birthday" else "WEDDING_EVENT"
    status = event["status"]
    owner_id = event["created_user_id"]

    if status == "DELETED":
        report["skipped_deleted"].append(str(old_id))
        return

    manual_review = []
    vendor_link_ids = fetch_vendor_link_ids(pg_conn, old_id)
    if vendor_link_ids:
        manual_review.append(f"vendor links present in old event but not migrated (no vendor-id mapping "
                              f"exists yet) -- old vendor UUIDs: {vendor_link_ids}. Link manually via "
                              f"POST /api/v1/events/{{id}}/vendors once the corresponding VENUE/"
                              f"PHOTOGRAPHER/CATERER/DECORATOR record UUIDs are known.")

    address, has_extra_address = fetch_address(pg_conn, old_id)
    if has_extra_address:
        manual_review.append("event had more than one address; only the first was migrated")
    base = build_base_data(event, address)

    if object_type == "BIRTHDAY_EVENT":
        mongo_doc = mongo_db.birthday_details.find_one({"eventId": str(old_id)})
        data = build_birthday_data(base, mongo_doc, manual_review)
    else:
        mongo_doc = mongo_db.wedding_details.find_one({"eventId": str(old_id)})
        data = build_wedding_data(base, mongo_doc, manual_review)

    print(f"  creating {object_type} for old event {old_id} (owner {owner_id})")
    new_event_id = client.create_event(owner_id, object_type, data)

    for trigger in STATUS_TRANSITIONS.get(status, []):
        print(f"    transition: {trigger}")
        client.transition(new_event_id, owner_id, trigger)
        time.sleep(0.2)  # transitions are async (outbox + Kafka); give it a beat between hops

    for member in fetch_members(pg_conn, old_id):
        if member["user_id"] == owner_id:
            continue  # owner is auto-added as ADMIN by create_event
        print(f"    inviting member {member['user_id']} as {member['role']} (was {member['status']})")
        client.invite_member(new_event_id, owner_id, member["user_id"], member["role"])
        if member["status"] == "ACCEPTED":
            client.respond_to_invite(new_event_id, member["user_id"], True)
        elif member["status"] == "DECLINED":
            client.respond_to_invite(new_event_id, member["user_id"], False)
        # INVITED members are left as INVITED -- that's the accurate historical state.

    for jr in fetch_join_requests(pg_conn, old_id):
        print(f"    recreating pending join request from {jr['user_id']}")
        client.create_join_request(new_event_id, jr["user_id"], jr["requested_role"], jr.get("message"))

    report["migrated"].append({"old_event_id": str(old_id), "new_event_id": new_event_id, "object_type": object_type})
    if manual_review:
        report["manual_review"][str(old_id)] = manual_review


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="Print planned API calls, make no real requests")
    parser.add_argument("--limit", type=int, default=None, help="Only process the first N eligible events")
    args = parser.parse_args()

    # Source DSNs are required even for --dry-run: reading real event-nest data is the whole
    # point of a dry run (to see what WOULD be migrated). --dry-run only skips the writes to
    # event-service, so PLATFORM_GATEWAY_SHARED_SECRET is the one var that's optional here.
    missing = [name for name, val in [
        ("EVENTNEST_PG_DSN", EVENTNEST_PG_DSN),
        ("EVENTNEST_MONGO_URI", EVENTNEST_MONGO_URI),
    ] if not val]
    if not args.dry_run:
        if not GATEWAY_SECRET:
            missing.append("PLATFORM_GATEWAY_SHARED_SECRET")
    if missing:
        print(f"Missing required env vars: {', '.join(missing)}", file=sys.stderr)
        sys.exit(1)

    pg_conn = psycopg2.connect(EVENTNEST_PG_DSN)
    mongo_db = pymongo.MongoClient(EVENTNEST_MONGO_URI).get_default_database()
    client = EventServiceClient(EVENT_SERVICE_BASE_URL, GATEWAY_SECRET, args.dry_run)

    report = {"migrated": [], "skipped_deleted": [], "manual_review": {}}

    events = fetch_events(pg_conn, args.limit)
    print(f"Found {len(events)} Birthday/Wedding events to consider.")
    for event in events:
        try:
            migrate_event(pg_conn, mongo_db, client, event, report)
        except Exception as e:
            print(f"  ERROR migrating event {event['id']}: {e}", file=sys.stderr)
            report.setdefault("errors", []).append({"old_event_id": str(event["id"]), "error": str(e)})

    with open("manual_review_report.json", "w") as f:
        json.dump(report, f, indent=2, default=str)

    print(f"\nDone. Migrated {len(report['migrated'])}, skipped {len(report['skipped_deleted'])} deleted, "
          f"{len(report['manual_review'])} events need manual review. See manual_review_report.json.")


if __name__ == "__main__":
    main()
