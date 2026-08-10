rootProject.name = "lagu-platform"

include(
    "libs:common",
    "libs:events",
    "libs:security",
    "libs:membership",
    "libs:storage",
    "apps:schema-registry",
    "apps:record-service",
    "apps:workflow-service",
    "apps:search-service",
    "apps:automation-service",
    "apps:notification-service",
    "apps:document-service",
    "apps:vendor-service",
    "apps:listing-service",
    "apps:event-service",
    "apps:booking-service",
    "apps:integration-test"
)
