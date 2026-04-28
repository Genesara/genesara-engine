plugins {
    id("agenticrpg.kotlin-library")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-events-api")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
}