plugins {
    alias(libs.plugins.homeassistant.android.application)
    alias(libs.plugins.google.services)
}

android {
    namespace = "io.homeassistant.companion.android.tv"

    defaultConfig {
        applicationId = "io.homeassistant.companion.android.tv"
        minSdk = libs.versions.androidSdk.min.get().toInt()
        targetSdk = libs.versions.androidSdk.target.get().toInt()

        versionName = project.version.toString()
        // We add 2 because the app (0) and wear (1) versions need to have different version codes.
        versionCode = 2 + checkNotNull(versionCode) { "Did you forget to apply the convention plugin that set the version code?" }
    }
}

dependencies {
    implementation(project(":common"))

    coreLibraryDesugaring(libs.tools.desugar.jdk)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.material)

    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.android)

    implementation(libs.iconics.core)
    implementation(libs.appcompat)
    implementation(libs.community.material.typeface)
    implementation(libs.iconics.compose)

    implementation(libs.activity.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.uiTooling)

    // TV-specific Compose libraries
    implementation(libs.tv.foundation)
    implementation(libs.tv.material)

    implementation(libs.guava)

    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
