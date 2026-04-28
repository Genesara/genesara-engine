plugins {
    id("agenticrpg.spring-module")
    id("agenticrpg.jooq-module")
}

dependencies {
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
}

jooqModule {
    migrationsSubdir.set("admin")
    tableIncludes.set("admins|admin_credentials|admin_tokens")
}
