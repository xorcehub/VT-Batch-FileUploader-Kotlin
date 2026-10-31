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

// Release version override, used by CI: ./gradlew :desktop:packageMsi -PappVersion=v1.2.3
// The git tag is passed as-is, so strip the leading "v" and any non-numeric
// suffix (e.g. v1.2.3-beta -> 1.2.3) — installer versions must be purely
// numeric. Padded to exactly 3 parts (all target formats require that), and
// an all-zero result falls back to the default below — e.g. an accidentally
// empty "0.0." must not fail the build configuration.
val appVersion = (findProperty("appVersion") as String?)
    ?.trim()
    ?.trimStart('v')
    ?.takeWhile { it.isDigit() || it == '.' }
    ?.trimEnd('.')
    ?.takeIf { it.isNotEmpty() }
    ?.split('.')
    ?.take(3)
    ?.let { it + List(3 - it.size) { "0" } }
    ?.joinToString(".")
    ?.takeIf { it != "0.0.0" }

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

            // Ship third-party license texts with every binary distribution
            // (Apache-2.0 §4(b), LGPL/EPL, MIT). Sources: desktop/packaging/common/licenses,
            // listed in THIRD-PARTY.md.
            appResourcesRootDir = project.layout.projectDirectory.dir("packaging")

            windows {
                menuGroup = "Xorce"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

                // CI-provided override: rolling "latest" builds use 0.0.<run
                // number>, v* tags use the tag version. Windows Installer
                // accepts a leading-zero major, macOS doesn't — so the
                // override lives here and the global packageVersion above
                // stays at 1.0.0 for every other format.
                appVersion?.let { packageVersion = it }
            }
        }
    }
}
