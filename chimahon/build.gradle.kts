plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "mihon.chimahon"
    ndkVersion = "29.0.14206865"
    buildFeatures { buildConfig = true }

    defaultConfig {
        externalNativeBuild {
            cmake { targets += listOf("dictionary_core", "word_audio_jni") }
        }
        buildConfigField("boolean", "NATIVE_DICTIONARY_CORE", "true")
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        getByName("debug") {
            externalNativeBuild {
                cmake {
                    // Default is arm64-v8a. Local emulator runs can select another ABI with
                    // -Pchimahon.debugAbi=x86_64 without changing release ABI filters.
                    abiFilters(
                        *(project.findProperty("chimahon.debugAbi") as String? ?: "arm64-v8a")
                            .split(",").map(String::trim).toTypedArray(),
                    )
                }
            }
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    if (providers.gradleProperty("includeLocalDictionaryHarness").orNull == "true") {
        sourceSets.getByName("test").java.srcDir("../tools/dictionary-harness/tests/renderer/java")
    }
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
    implementation(libs.okhttp.core)
    implementation(libs.image.decoder)
    implementation(androidx.corektx)
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)
    implementation(kotlinx.serialization.protobuf)
    implementation(kotlinx.serialization.json)
    
    // Compose
    implementation(compose.activity)
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.material.icons)
    implementation(compose.animation)
    implementation(compose.ui.util)
    implementation(compose.ui.tooling.preview)

    // ChimaReader
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    implementation(libs.preferencektx)
    implementation(libs.compose.webview)
    implementation(libs.jsoup)
    implementation(libs.datastore.preferences)
    implementation(libs.bundles.media3)

    // Zoomable image viewer for novel illustrations
    implementation(libs.subsamplingscaleimageview) {
        exclude(module = "image-decoder")
    }

    // Dependency injection
    implementation(libs.injekt)
    
    // Mihon core
    implementation(projects.core.common)
    implementation(libs.unifile)

    testImplementation(libs.bundles.test)
    testImplementation(kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
