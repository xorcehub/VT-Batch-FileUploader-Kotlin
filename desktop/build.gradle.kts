plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
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

// "desktop" is the GUI app module. It uses Compose Multiplatform to build
// a native desktop UI (like Electron but uses the OS's native rendering,
// so it's fast and lightweight — no Chromium bundled).
//
// Compose = Google's modern UI toolkit (originally for Android).
// Compose Multiplatform = JetBrains' extension that makes it work on Desktop too.
// You write UI as Kotlin functions annotated with @Composable.

dependencies {
    implementation(project(":shared"))

    // Compose Desktop UI toolkit
    implementation(compose.desktop.currentOs)

    // Material3 — Google's design system. Pre-built components like
    // buttons, text fields, cards, etc. with consistent styling.
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines with Swing integration — lets us update the UI from
    // background threads safely (Swing = Java's built-in GUI toolkit
    // that Compose Desktop renders on top of)
    implementation(libs.kotlinx.coroutines.swing)

    // Logging
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Configure how the desktop app runs and gets packaged
compose.desktop {
    application {
        mainClass = "com.vtbatch.desktop.MainKt"

        // JVM arguments for the runtime
        jvmArgs += listOf("-Xmx512m")

        // Native packaging — produces installers for each OS
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,     // Windows
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,     // macOS
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,     // Linux (Debian)
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm      // Linux (RedHat)
            )

            packageName = "VT-Batch-FileUploader"
            packageVersion = "1.0.0"

            windows {
                menuGroup = "Xorce"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}
