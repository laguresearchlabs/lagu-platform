// Phase 5 — not yet implemented
// See todo/08-phase5-automation-service.md
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:events"))
    implementation(project(":libs:security"))
    implementation(rootProject.libs.spring.boot.web)
    implementation(rootProject.libs.spring.boot.kafka)
    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)
}
