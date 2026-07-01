rootProject.name = "lagu-platform"

include(
    "libs:common",
    "libs:events",
    "libs:security",
    "apps:schema-registry",
    "apps:metadata-service",
    "apps:record-service",
    "apps:workflow-service",
    "apps:search-service",
    "apps:automation-service",
    "apps:notification-service",
    "apps:document-service",
    "apps:vendor-service",
    "apps:listing-service"
)
