plugins {
    id("genesara.spring-module")
    id("genesara.jooq-module")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":account"))
    implementation("org.springframework.boot:spring-boot-starter-jooq")

    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation("com.zaxxer:HikariCP")
}

jooqModule {
    migrationsSubdir.set("player")
    tableIncludes.set("agents|agent_profiles|agent_skills|agent_skill_slots|agent_skill_recommendations")
}
