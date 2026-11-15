package app.picacgmd.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

internal fun VersionCatalog.version(name: String): String = findVersion(name).get().requiredVersion

internal fun Project.configureAndroid(extension: CommonExtension) {
    val versions = libs
    val jvmTarget = versions.version("jvmTarget").toInt()
    extension.compileSdk {
        version = release(versions.version("compileSdk").toInt()) {
            minorApiLevel = versions.version("compileSdkMinor").toInt()
        }
    }
    extension.buildToolsVersion = versions.version("buildTools")
    extension.defaultConfig.minSdk = versions.version("minSdk").toInt()
    extension.compileOptions.sourceCompatibility = JavaVersion.toVersion(jvmTarget)
    extension.compileOptions.targetCompatibility = JavaVersion.toVersion(jvmTarget)
    extensions.configure(KotlinAndroidProjectExtension::class.java) { kotlin ->
        kotlin.jvmToolchain(jvmTarget)
    }
}
