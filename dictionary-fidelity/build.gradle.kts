plugins {
    id("mihon.android.application.compose")
}

kotlin {
    compilerOptions {
        // The library convention sets this; the application convention does not, and Kotlin would
        // otherwise default to a newer target than the Java compile.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "eu.kanade.tachiyomi.ui.dictionary.bench"
    compileSdk = 36

    defaultConfig {
        // A distinct id from the app: the fidelity loop installs and replaces this one on its own,
        // and never touches the app's build.
        applicationId = "app.chimahon.fidelity"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        val debug by getting {
            isMinifyEnabled = false
        }
        val release by getting {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by the dependencies (chimahon and the renderer both desugar). It costs nothing
        // here: the app's dex took 25 minutes with desugaring over 1589 files, and this module
        // carries the harness and its dependencies only.
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    // The library modules (chimahon, the renderer) enable core library desugaring, and their AAR
    // metadata requires the consumer to enable it too.
    coreLibraryDesugaring(libs.desugar)
    implementation(projects.dictionaryRenderer)
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.ui.util)
}
