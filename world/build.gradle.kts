plugins {
    id("genesara.spring-module")
}

dependencies {
    api(project(":world:core"))
    api(project(":world:body"))
    api(project(":world:combat"))
    api(project(":world:economy"))
    api(project(":world:environment"))
    implementation(project(":engine"))
    implementation(project(":player"))
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(project(":account"))
    testImplementation(testFixtures(project(":world:core")))
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation("com.zaxxer:HikariCP")
}
