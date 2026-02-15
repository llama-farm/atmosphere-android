// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
}

// Rust build task - runs before Android build
tasks.register("buildRust") {
    description = "Build Rust libraries for all Android targets"
    group = "build"
    
    doLast {
        val rustBuildScript = file("rust/build-android.sh")
        if (rustBuildScript.exists()) {
            exec {
                workingDir = file("rust")
                commandLine("bash", "build-android.sh")
            }
        } else {
            logger.warn("Rust build script not found at rust/build-android.sh")
        }
    }
}

// Clean Rust artifacts
tasks.register("cleanRust") {
    description = "Clean Rust build artifacts"
    group = "build"
    
    doLast {
        val rustTarget = file("rust/target")
        if (rustTarget.exists()) {
            delete(rustTarget)
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
