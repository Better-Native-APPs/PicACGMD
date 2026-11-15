package app.picacgmd.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        target.pluginManager.withPlugin("com.android.application") {
            target.extensions.getByType(ApplicationExtension::class.java).buildFeatures.compose = true
        }
        target.pluginManager.withPlugin("com.android.library") {
            target.extensions.getByType(LibraryExtension::class.java).buildFeatures.compose = true
        }
    }
}
