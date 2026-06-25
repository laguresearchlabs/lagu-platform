rootProject.name = "lagu-platform"

include(
    "libs:common",
    "libs:events",
    "libs:security",
    "apps:metadata-service",
    "apps:record-service",
    "apps:workflow-service",
    "apps:search-service",
    "apps:automation-service"
)
