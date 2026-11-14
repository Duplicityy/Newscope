plugins { id("com.android.application") }
android {
    namespace = "local.kronos.mobile"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    ndkVersion = "27.0.12077973"
    packaging { jniLibs { useLegacyPackaging = true } }
    defaultConfig {
        applicationId = "local.kronos.mobile"
        minSdk = 28
        targetSdk = 35
        versionCode = 9
        versionName = "0.4.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
}
tasks.register("verifyKronosAssets") {
    doLast {
        check(file("src/main/AndroidManifest.xml").readText().contains("android:allowNativeHeapPointerTagging=\"false\"")) { "Embedded HotSpot requires native heap pointer tagging disabled; see RuneMobile docs/dev/android.md." }
        listOf("jre/lib/server/libjvm.so", "jre/lib/libawt_xawt.so", "client.jar", "bridge.jar", "cacio-tta-1.18.jar", "cacio-shared-1.18.jar").forEach {
            check(file("src/main/assets/jvm/$it").isFile) { "Missing $it. Run Build-Kronos-Mobile.ps1 first." }
        }
    }
}
tasks.named("preBuild") { dependsOn("verifyKronosAssets") }
