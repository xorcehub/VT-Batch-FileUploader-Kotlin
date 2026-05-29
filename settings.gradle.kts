pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "VT-Batch-FileUploader-Kotlin"

include(":shared")
include(":desktop")
include(":cli")
