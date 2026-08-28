# booking-service

Owns the consumer↔vendor booking lifecycle: a consumer inquires about a published listing, the
vendor prices it, the consumer accepts, and the date is claimed on the vendor's availability
calendar. Implemented, built, routed through the gateway and deployed.

> **Note on earlier revisions of this file.** Until recently this README opened with "Status:
> scaffold only — not implemented, not wired into the build" and stated there were no source
> files and no migrations. That was written against the empty package skeleton and was never
> updated when the service landed; `docs/architecture.md` inherited the same claim. Both are
> corrected. If you are reconciling old notes: there is nothing scaffold-like here.

## State machine

```
INQUIRY ──quote──> QUOTED ──confirm──> CONFIRMED ──complete──> COMPLETED
   │                  │                    │
   └──────────────────┴────────────────────┴──cancel──> CANCELLED
```

Deliberately booking-service's own small, fixed state machine rather than a record-service Record
driven by workflow-service. Two reasons, both in `V1__booking_schema.sql`:

- **The rules are asymmetric and two-party.** Only the vendor org may quote
  (`requireVendorSide`); only the consumer may confirm (`requireConsumer`); either may cancel or
  complete. That is not the admin-configurable, single-actor shape workflow-service models.
- **Confirm has to claim an availability slot atomically** with the local status change. An async
  workflow round-trip is a poor fit for an operation that must not half-happen.

`complete` is additionally refused before the event date (409 `EVENT_NOT_YET_OCCURRED`).

## API — `/api/v1/bookings`

| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/` | consumer | Listing must be `PUBLISHED`, else 409 `LISTING_NOT_BOOKABLE` |
| GET | `/{id}` | either party | |
| GET | `/mine` | consumer | Optional `?eventId=` filter |
| GET | `/vendor` | vendor org | Scoped by `X-Tenant-Id`; 403 without one |
| POST | `/{id}/quote` | vendor org | From `INQUIRY` only |
| POST | `/{id}/confirm` | **consumer only** | Claims the availability slot |
| POST | `/{id}/complete` | either party | From `CONFIRMED`, on/after the event date |
| POST | `/{id}/cancel` | either party | Releases the slot if it was `CONFIRMED` |

Vendor-side actions are driven from `events-vp` (the vendor portal); consumer-side from
`events-ui`.

## Commission

`quote` reads the commission rate from schema-registry's `TierConfiguration` for the listing's
verification tier and object type, then **freezes both the rate and the computed amount** onto the
booking row. A later tier-config change does not retroactively alter a quote a vendor already
sent. The rate is a percentage (`20.00` means 20%), matching `TierConfiguration.commissionRate`.

Note that nothing in the platform collects this. The commission is recorded, not charged — there
is no payment, payout or settlement code anywhere in lagu-platform today.

## Events

Publishes `BookingEvent` to `platform.booking.events` through the shared transactional outbox
(`booking_outbox`), so the event and the status change commit or roll back together. Consumed by
automation-service (group `automation-service-booking`), which raises notifications for **both**
parties.

Two fields on the event exist solely so automation-service can express "notify the other side",
because its `ConditionEvaluator` compares a field to a constant and never one field to another:

- **`actorSide`** — `CONSUMER` or `VENDOR`. Cancel and complete are open to either party, so
  without this a vendor cancelling a booking would be notified that their own booking had been
  cancelled. Computed as "the actor is not the consumer, therefore the vendor", which holds
  because the endpoint guards admit nobody else.
- **`vendorRecipientUserId`** and **`vendorRecipientEmail`** — who to notify and where to email
  them, both resolved from vendor-service at publish time via
  `GET /internal/memberships/{tenantId}/owner`. That endpoint returns the org's active OWNER
  together with the `email` field of its VENDOR record — the **business contact address**, not the
  owner's login address, which lives in IAM behind user-authenticated endpoints that a service
  caller cannot reach. Business contact is also the better address on the merits.

  The lookup is **best-effort**: a vendor-service outage costs the notification, not the booking.
  A failed lookup publishes a null recipient, and the seeded vendor triggers carry an
  `IS_NOT_NULL` condition on the recipient id so they skip rather than address a notification to
  nobody. There is deliberately no such condition on the email — that field is optional on the
  VENDOR schema, and a blank one means send less (in-app only), not send nothing.

  **Known limit:** only the owner is notified. A vendor org can have ADMIN/MEMBER users who
  actually handle bookings, and none of them hear anything. Widening this needs recipient fan-out
  in notification-service, not a change here — the event already names the org.

## Outbound calls

All via a Eureka-discovered load-balanced `RestClient`, carrying `X-Internal-Service:
booking-service` and the gateway shared secret.

| Client | Target | Failure mode |
|---|---|---|
| `ListingServiceClient.getSnapshot` | listing-service | **Fails closed** — no listing, no inquiry |
| `ListingServiceClient.bookSlot` / `releaseSlot` | listing-service | **Fails loud** — a network error must not be mistaken for a lost race, or leave a slot wrongly held |
| `SchemaRegistryClient.getCommissionRate` | schema-registry | Falls back rather than blocking a quote |
| `VendorServiceClient.findNotificationTarget` | vendor-service | **Best-effort** — costs a notification only |

## Configuration

`application.yml`; `application-loc.yml` is for local non-Docker runs only and must never be the
active profile in an image.

- Postgres via `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`, Flyway-managed `booking` schema
- Kafka via `KAFKA_BOOTSTRAP`; outbox enabled, table `booking_outbox`
- HTTP port `SERVER_PORT`, default `8109`
- Eureka via `EUREKA_SERVER_URL`
- Actuator: `health`, `info`, `prometheus`

## Tests

16 main classes, 4 test classes. `BookingServiceTest` covers the state machine and authorization
against mocks; `BookingEventPublisherTest` covers notification routing; `BookingControllerTest`
covers caller-identity handling; `BookingServiceIntegrationTest` runs the HTTP API against real
Postgres (Testcontainers) and embedded Kafka, and asserts a confirmed booking's event genuinely
reaches the topic through the outbox relay rather than merely being staged.

```bash
./gradlew :apps:booking-service:test      # Testcontainers needs a running Docker daemon
```
