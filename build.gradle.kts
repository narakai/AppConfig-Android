plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Coordinates for JitPack: com.github.narakai.AppConfig-Android:<module>:<tag>
allprojects {
    group = "com.github.narakai.AppConfig-Android"
    version = "0.2.0"
}
