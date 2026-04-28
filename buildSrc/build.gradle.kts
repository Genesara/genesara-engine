plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven { url = uri("https://repo.spring.io/milestone") }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    implementation("org.jetbrains.kotlin:kotlin-allopen:2.3.20")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.0-M4")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
    implementation("org.graalvm.buildtools:native-gradle-plugin:0.11.5")
    implementation("nu.studer:gradle-jooq-plugin:9.0")
}