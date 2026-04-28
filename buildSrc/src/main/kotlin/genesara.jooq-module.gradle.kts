import nu.studer.gradle.jooq.JooqEdition
import nu.studer.gradle.jooq.JooqGenerate
import org.gradle.api.provider.Property
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jooq.meta.jaxb.Logging
import org.jooq.meta.jaxb.Property as JooqProperty

plugins {
    id("nu.studer.jooq")
}

abstract class JooqModuleExtension {
    abstract val migrationsSubdir: Property<String>
    abstract val tableIncludes: Property<String>
}

val jooqModule = extensions.create<JooqModuleExtension>("jooqModule")

dependencies {
    "jooqGenerator"("org.jooq:jooq-meta-extensions:3.21.0")
}

extensions.getByType<KotlinJvmProjectExtension>()
    .sourceSets["main"].kotlin
    .srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))

afterEvaluate {
    val migrationsSubdirValue = jooqModule.migrationsSubdir.get()
    val tableIncludesValue = jooqModule.tableIncludes.get()
    val migrationsDir = layout.projectDirectory.dir("src/main/resources/db/migration/$migrationsSubdirValue")
    val pkg = "dev.gvart.genesara.${project.name}.internal.jooq"

    jooq {
        version.set("3.21.0")
        edition.set(JooqEdition.OSS)
        configurations {
            create("main") {
                generateSchemaSourceOnCompilation.set(true)
                jooqConfiguration.apply {
                    logging = Logging.WARN
                    generator.apply {
                        name = "org.jooq.codegen.KotlinGenerator"
                        database.apply {
                            name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                            properties = listOf(
                                // V*.sql only — Flyway repeatable migrations (R__*.sql) often hold
                                // CREATE FUNCTION / DO blocks that jOOQ OSS DDLDatabase cannot parse.
                                JooqProperty().withKey("scripts").withValue("${migrationsDir.asFile.absolutePath}/V*.sql"),
                                JooqProperty().withKey("sort").withValue("flyway"),
                                JooqProperty().withKey("defaultNameCase").withValue("lower"),
                                JooqProperty().withKey("parseDialect").withValue("POSTGRES"),
                            )
                            includes = tableIncludesValue
                        }
                        target.apply {
                            packageName = pkg
                        }
                        generate.apply {
                            isPojos = false
                            isRecords = true
                            isFluentSetters = false
                            isDaos = false
                            isKotlinNotNullPojoAttributes = true
                            isKotlinNotNullRecordAttributes = true
                        }
                    }
                }
            }
        }
    }

    tasks.named<JooqGenerate>("generateJooq") {
        inputs.files(fileTree(migrationsDir))
            .withPropertyName("migrations")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        allInputsDeclared.set(true)
    }
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("generateJooq"))
}
