package app.picacgmd.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.application")
        target.extensions.configure(ApplicationExtension::class.java) { android ->
            target.configureAndroid(android)
            android.defaultConfig.targetSdk = target.libs.version("targetSdk").toInt()
        }
    }
}
