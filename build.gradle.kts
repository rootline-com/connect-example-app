// Root build script. Plugins are declared here (applied in the :app module) so their versions
// resolve once from the version catalog.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
