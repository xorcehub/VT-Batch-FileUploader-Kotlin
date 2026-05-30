plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Target JVM 21 bytecode — runs on any Java 21+ installation.
// We set the compiler target (not the toolchain) so it works with JDK 25 installed.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// "shared" is a plain Kotlin library — no UI, no platform-specific stuff.
// Both desktop and cli modules depend on this.
// kotlinx.serialization = converts Kotlin objects to/from JSON automatically
//   (like Python's json.dumps / json.loads but type-safe at compile time)

dependencies {
    // Coroutines — Kotlin's version of async/await. Like Python asyncio but
    // built into the language with structured concurrency.
    api(libs.kotlinx.coroutines.core)

    // JSON serialization — annotate a data class with @Serializable and it
    // can be converted to/from JSON automatically.
    // Using `api` so downstream modules (desktop, cli) can see JsonObject
    api(libs.kotlinx.serialization.json)

    // Ktor HTTP client — how we'll call the VirusTotal API.
    // Think of it like Python's `requests` library but async-first.
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.kotlin.logging)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
