plugins {
    id("genesara.spring-module")
    id("genesara.jooq-module")
}

dependencies {
    implementation(project(":engine"))
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation("com.zaxxer:HikariCP")
}

jooqModule {
    migrationsSubdir.set("admin")
    tableIncludes.set("admins|admin_credentials|admin_tokens|admin_audit_log")
}
