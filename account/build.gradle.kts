plugins {
    id("agenticrpg.spring-module")
    id("agenticrpg.jooq-module")
}

dependencies {
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
}

jooqModule {
    migrationsSubdir.set("account")
    tableIncludes.set("players|player_credentials")
}
