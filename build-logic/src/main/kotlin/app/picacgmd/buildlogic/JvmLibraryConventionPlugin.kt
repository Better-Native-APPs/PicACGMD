package app.picacgmd.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("java-library")
        target.pluginManager.apply("org.jetbrains.kotlin.jvm")
        val jvmTarget = target.libs.version("jvmTarget").toInt()
        target.extensions.configure(KotlinJvmProjectExtension::class.java) { kotlin ->
            kotlin.jvmToolchain(jvmTarget)
        }
    }
}
