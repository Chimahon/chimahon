plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    // The renderer keeps the eu.kanade.tachiyomi.ui.dictionary.compose package, so the app and
    // the debug harness need no import changes - only the `internal` declarations that they
    // reach became public.
    namespace = "eu.kanade.tachiyomi.ui.dictionary.compose"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    api(projects.chimahon)
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.ui.util)
    implementation(compose.ui.tooling.preview)
    implementation(libs.jsoup)
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
