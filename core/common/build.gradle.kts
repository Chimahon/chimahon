plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
    id("com.github.ben-manes.versions")
}

android {
    namespace = "eu.kanade.tachiyomi.core.common"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    implementation(projects.i18n)
    // SY -->
    implementation(projects.i18nSy)
    // SY <--

    api(libs.logcat)

    api(libs.rxjava)

    api(libs.okhttp.core)
    api(libs.okhttp.logging)
    api(libs.okhttp.brotli)
    api(libs.okhttp.dnsoverhttps)
    api(libs.okio)

    implementation(libs.image.decoder)

    implementation(libs.unifile)
    implementation(libs.libarchive)

    api(kotlinx.coroutines.core)
    api(kotlinx.serialization.json)
    api(kotlinx.serialization.json.okio)

    api(libs.preferencektx)

    implementation(libs.jsoup)

    // Sort
    implementation(libs.natural.comparator)

    // JavaScript engine. The app.cash.quickjs API extensions link against is provided by
    // the shim in this module, implemented on top of this engine. Do not also depend on
    // com.github.zhanghai.quickjs-java: the two ship libquickjs.so at the same APK path
    // with different JNI ABIs, and the loser fails every native call at runtime.
    implementation(libs.quickjs.kt)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // SY -->
    implementation(sylibs.xlog)
    implementation(sylibs.exifinterface)
    // SY <--

    implementation(libs.injekt)
    implementation(libs.torrentserver)
}
