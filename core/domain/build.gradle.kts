plugins {
    id("picacgmd.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.picacgmd.core.domain"
}

dependencies {
    api(project(":core:network"))
    api(project(":core:data"))
    api(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
