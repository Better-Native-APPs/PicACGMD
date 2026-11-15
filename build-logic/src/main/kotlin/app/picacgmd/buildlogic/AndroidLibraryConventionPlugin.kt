package app.picacgmd.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.library")
        target.extensions.configure(LibraryExtension::class.java) { android ->
            target.configureAndroid(android)
        }
    }
}
