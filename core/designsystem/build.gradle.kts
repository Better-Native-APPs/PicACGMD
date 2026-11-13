plugins {
    id("picacgmd.android.library")
    id("picacgmd.android.compose")
}

android {
    namespace = "app.picacgmd.core.designsystem"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    // Used directly (launch in shimmer / status animations); also pins the catalog version.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.kotlin.test.junit)
}
