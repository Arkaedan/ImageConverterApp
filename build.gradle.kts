plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 provides built-in Kotlin support; declaring the Kotlin plugin here only pins its version.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
