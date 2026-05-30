plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// "cli" module — the command-line interface version of the app.
// Uses the same shared business logic as the GUI, just with text output.
// The `application` plugin lets us run it via `gradlew cli:run --args="..."`.

dependencies {
    implementation(project(":shared"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // picocli — a CLI argument parsing library. You annotate a class with
    // @Command, @Option, @Parameters and it auto-generates help text,
    // parses args, handles errors. Like Python's `argparse` but more powerful.
    implementation(libs.picocli)

    // Logging
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.vtbatch.cli.MainKt")
}
