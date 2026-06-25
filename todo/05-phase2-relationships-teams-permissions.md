# Phase 2 — Relationships, Teams & Access Control

## What Phase 2 Adds

Three closely related capabilities that must ship together because they depend on each other:

1. **Relationship Engine** — define and enforce inter-record links (Event → Vendor, Org → Venue)
2. **Organization / Team Engine** — org hierarchy with groups and member roles
3. **Role-Based Access Control** — platform roles, business roles, and custom roles

All three extend the metadata-service (new tables) and the record-service (new enforcement).
No new top-level microservice yet — ship as new modules within existing services.

---

## Part A: Relationship Engine

### When to use relationships vs. ENTITY_REFERENCE attributes

| Use case                                    | Approach                        |
|---------------------------------------------|---------------------------------|
| "Which city is this venue in?"              | `city` attribute (TEXT/ENUM)    |
| "Which Photographer is booked for Event X?" | `RelationshipDefinition`        |
| "Which budget items belong to Event Y?"     | `PARENT_CHILD` relationship     |

Rule: scalar lookups → attributes. Object-to-object links → relationships.

### New Tables (in metadata schema)

```sql
-- Already created in Phase 1 (placeholder). Now enforce FK logic.
-- relationship_definition already exists.

-- Relationship instances (stored in records schema)
CREATE TABLE records.record_relationship (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID        NOT NULL,
    relationship_def_id     UUID        NOT NULL REFERENCES metadata.relationship_definition(id),
    source_record_id        UUID        NOT NULL REFERENCES records.record(id),
    target_record_id        UUID        NOT NULL REFERENCES records.record(id),
    created_by              UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_source_target_rel UNIQUE (relationship_def_id, source_record_id, target_record_id)
);

CREATE INDEX idx_rel_source ON records.record_relationship (org_id, source_record_id);
CREATE INDEX idx_rel_target ON records.record_relationship (org_id, target_record_id);
```

### API Additions to record-service

```
POST   /records/{id}/relationships
Body:  { "relationshipName": "EVENT_VENDORS", "targetRecordId": "uuid" }

GET    /records/{id}/relationships/{relationshipName}
       → list of related records (with their data)

DELETE /records/{id}/relationships/{relationshipName}/{targetId}
```

### Validation

Before creating a relationship instance:
- Look up `RelationshipDefinition` by name
- Confirm `source_record.object_type` matches `source_object_type`
- Confirm `target_record.object_type` matches `target_object_type`
- For `ONE_TO_ONE`: verify no existing relationship from same source
- For `PARENT_CHILD` with `cascade_delete`: enforce deletion propagation

---

## Part B: Organization & Team Engine

### Concept

```
Organization (org_id from IAM)
    │
    ├── Group ("Photography Team", "Venue Management Team")
    │       │
    │       └── Member (user_id + role_in_group)
    │
    └── Group ("Kitchen Staff")
            │
            └── Member
```

`Organization` is not a stored entity — it's the `org_id` from the JWT (set by IAM).
Platform just stores groups and members scoped to that org.

### Schema (new schema: `teams`)

```sql
CREATE TABLE teams.group_definition (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID        NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_active   BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_group_name_org UNIQUE (name, org_id)
);

CREATE TABLE teams.group_member (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id    UUID        NOT NULL REFERENCES teams.group_definition(id) ON DELETE CASCADE,
    org_id      UUID        NOT NULL,
    user_id     UUID        NOT NULL,   -- from IAM user service
    role_name   VARCHAR(100),           -- custom role (see Part C)
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_group_member UNIQUE (group_id, user_id)
);

CREATE INDEX idx_group_org      ON teams.group_definition (org_id);
CREATE INDEX idx_member_user    ON teams.group_member (org_id, user_id);
CREATE INDEX idx_member_group   ON teams.group_member (group_id);
```

### Team API

New microservice: **team-service** (or add to metadata-service as a module).

Recommendation: Add as a module in metadata-service for Phase 2, extract to its own
service if it grows complex.

```
GET    /groups                         List org's groups
POST   /groups                         Create group
GET    /groups/{id}                    Get group with members
POST   /groups/{id}/members            Add member
DELETE /groups/{id}/members/{userId}   Remove member
PUT    /groups/{id}/members/{userId}   Update member's role
```

---

## Part C: Role-Based Access Control

### Role Model

```
Platform Roles (hardcoded, platform-level)
    PLATFORM_ADMIN    — full access to all orgs, all config
    CONFIG_ADMIN      — manage metadata definitions for their org
    ORG_USER          — standard user in an org

Business Roles (defined by platform, scoped per org)
    ORG_OWNER
    ORG_MANAGER
    ORG_STAFF

Custom Roles (created by CONFIG_ADMIN, stored in DB)
    PHOTOGRAPHER
    CHEF
    VENUE_COORDINATOR
    EVENT_HOST
    etc.
```

### Schema (in `metadata` schema)

```sql
CREATE TABLE metadata.role_definition (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID,               -- NULL = platform-level role
    name        VARCHAR(100) NOT NULL,
    label       VARCHAR(200) NOT NULL,
    description TEXT,
    role_level  VARCHAR(20)  NOT NULL,  -- PLATFORM, BUSINESS, CUSTOM
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_role_name_org UNIQUE (name, org_id)
);

CREATE TABLE metadata.permission_definition (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_type   VARCHAR(100) NOT NULL,  -- OBJECT_TYPE name or "*"
    action          VARCHAR(50)  NOT NULL,  -- CREATE, READ, UPDATE, DELETE, TRANSITION, APPROVE
    role_id         UUID         NOT NULL REFERENCES metadata.role_definition(id) ON DELETE CASCADE,
    conditions      JSONB,                  -- {"status": ["DRAFT", "SUBMITTED"]}
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_perm_resource_action_role UNIQUE (resource_type, action, role_id)
);

-- User-to-role assignment (org scoped)
CREATE TABLE metadata.user_role (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    role_id     UUID        NOT NULL REFERENCES metadata.role_definition(id),
    granted_by  UUID,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_role_org UNIQUE (org_id, user_id, role_id)
);
```

### Permission Check Flow

```
Request arrives at record-service
    │
    ▼
GatewayHeaderFilter extracts:
    X-User-Id      → userId
    X-Org-Id       → orgId
    X-User-Roles   → ["CONFIG_ADMIN", "ORG_MANAGER"]
    │
    ▼
PermissionEvaluator.canAccess(userId, orgId, "VENUE", "CREATE")
    │
    ├── Check platform roles first (from X-User-Roles header)
    ├── Load user_role from DB (custom roles for this org)
    ├── Check permission_definition for matching role + resource + action
    └── Check conditions (e.g., only allowed if record status = "DRAFT")
```

### `@RequirePermission` Annotation

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String resource();     // OBJECT_TYPE name or "*"
    String action();       // CREATE, READ, UPDATE, DELETE, TRANSITION, APPROVE
}

// Usage:
@PostMapping
@RequirePermission(resource = "#request.objectType", action = "CREATE")
public ResponseEntity<RecordResponse> create(@RequestBody CreateRecordRequest request) { ... }
```

Implement via Spring AOP aspect in `libs/security`.

### Role API (in metadata-service)

```
GET    /roles                    List roles for org
POST   /roles                    Create custom role (CONFIG_ADMIN only)
GET    /roles/{id}/permissions   List permissions for role
POST   /roles/{id}/permissions   Grant permission
DELETE /roles/{id}/permissions/{permId}  Revoke permission
POST   /roles/{id}/users         Assign role to user
DELETE /roles/{id}/users/{userId} Remove role from user
```

---

## Kafka Events

Topic: `platform.team.events`

```java
@Data @Builder
public class TeamEvent {
    private String  eventType;     // MEMBER_ADDED, MEMBER_REMOVED, ROLE_ASSIGNED, ROLE_REVOKED
    private UUID    orgId;
    private UUID    groupId;
    private UUID    userId;
    private String  roleName;
    private Instant occurredAt;
}
```

---

## Implementation Checklist

### Relationship Engine
- [ ] Finalize `relationship_definition` table constraints
- [ ] Create `record_relationship` table + migration
- [ ] Implement `RelationshipService` with type-specific validation
- [ ] Add relationship endpoints to record-service
- [ ] Publish relationship events to Kafka

### Team Engine
- [ ] Create `teams` schema + migrations
- [ ] Implement `GroupService` + `GroupMemberService`
- [ ] Add group/member API endpoints

### RBAC
- [ ] Create `role_definition` + `permission_definition` + `user_role` tables
- [ ] Implement `PermissionEvaluator` in `libs/security`
- [ ] Implement `@RequirePermission` AOP aspect
- [ ] Seed platform-level roles (PLATFORM_ADMIN, CONFIG_ADMIN, ORG_USER)
- [ ] Seed business roles (ORG_OWNER, ORG_MANAGER, ORG_STAFF)
- [ ] Add role/permission API to metadata-service
- [ ] Wire permission checks into record-service
- [ ] Integration tests for permission enforcement
