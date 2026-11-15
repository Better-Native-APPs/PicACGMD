plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(libs.android.gradle.plugin)
    // Keep AGP's built-in Kotlin and the JVM convention on the same compiler version.
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "picacgmd.android.application"
            implementationClass = "app.picacgmd.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "picacgmd.android.library"
            implementationClass = "app.picacgmd.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "picacgmd.android.compose"
            implementationClass = "app.picacgmd.buildlogic.AndroidComposeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "picacgmd.jvm.library"
            implementationClass = "app.picacgmd.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
