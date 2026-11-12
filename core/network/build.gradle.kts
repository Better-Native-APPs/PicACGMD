plugins {
    id("picacgmd.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
