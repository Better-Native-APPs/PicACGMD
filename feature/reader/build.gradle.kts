plugins {
    id("picacgmd.android.library")
    id("picacgmd.android.compose")
}

android {
    namespace = "app.picacgmd.feature.reader"
}

dependencies {
    implementation(project(":core:designsystem"))
    // StateFlow appears in the public ReaderSource contract.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.kotlin.test.junit)
}
