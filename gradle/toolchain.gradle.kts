// Deprecated helper script.
// This project now configures Android/Kotlin toolchains directly in each module
// using gradle/tools.versions.toml as single source of truth.
//
// Kept only for backward compatibility in local environments that still call:
//   apply(from = "gradle/toolchain.gradle.kts")

val jdkVersion = tools.versions.jdk.get().toInt()
val javaVersion = JavaVersion.toVersion(jdkVersion)

subprojects {
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.findByName("kotlin")?.let {
            (it as org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension).jvmToolchain(jdkVersion)
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.findByName("kotlin")?.let {
            (it as org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension).jvmToolchain(jdkVersion)
        }
    }

    plugins.withId("com.android.application") {
        extensions.findByType(com.android.build.api.dsl.ApplicationExtension::class.java)?.compileOptions {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
    }

    plugins.withId("com.android.library") {
        extensions.findByType(com.android.build.api.dsl.LibraryExtension::class.java)?.compileOptions {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
    }
}
