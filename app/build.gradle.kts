plugins {
    id("genesara.kotlin-library")
    id("org.springframework.boot")
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":world"))
    implementation(project(":player"))
    implementation(project(":admin"))
    implementation(project(":api"))

    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    developmentOnly("org.springframework.ai:spring-ai-spring-boot-docker-compose")

    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
}