plugins { id("com.android.application") }

// This isolated app must never silently reuse the public client's debug signer.
fun ownerSigningValue(property: String, environment: String): String? =
    providers.gradleProperty(property).orElse(providers.environmentVariable(environment)).orNull
val ownerStore = ownerSigningValue("ownerTestStoreFile", "OWNER_TEST_STORE_FILE")
val ownerStorePassword = ownerSigningValue("ownerTestStorePassword", "OWNER_TEST_STORE_PASSWORD")
val ownerAlias = ownerSigningValue("ownerTestKeyAlias", "OWNER_TEST_KEY_ALIAS")
val ownerKeyPassword = ownerSigningValue("ownerTestKeyPassword", "OWNER_TEST_KEY_PASSWORD")
val ownerTestHost = "51.38.80.2"

android {
    namespace = "local.kronos.mobile"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    ndkVersion = "27.0.12077973"
    packaging { jniLibs { useLegacyPackaging = true } }
    defaultConfig {
        applicationId = "local.newscape.desktoptest"
        minSdk = 28
        targetSdk = 35
        versionCode = 46
        versionName = "0.11.6-expedition"
        buildConfigField("boolean", "OWNER_TEST", "true")
        buildConfigField("String", "OWNER_TEST_HOST", "\"$ownerTestHost\"")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DNEWSCAPE_OWNER_HOST=$ownerTestHost"
            }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        create("ownerTest") {
            storeFile = ownerStore?.let { file(it) }
            storePassword = ownerStorePassword
            keyAlias = ownerAlias
            keyPassword = ownerKeyPassword
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("ownerTest") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("ownerTest") }
    }
}
tasks.register("verifyOwnerSigning") {
    doLast {
        check(!ownerStore.isNullOrBlank() && file(ownerStore!!).isFile) { "An explicit owner-test signing store is required." }
        check(!ownerStorePassword.isNullOrBlank() && !ownerAlias.isNullOrBlank() && !ownerKeyPassword.isNullOrBlank()) { "Owner-test signing properties are required; the public/debug signer is never a fallback." }
        check(file(ownerStore!!).name != "debug.keystore") { "Use the independent owner-test key, not a debug keystore." }
    }
}
tasks.register("verifyKronosAssets") {
    doLast {
        check(file("src/main/AndroidManifest.xml").readText().contains("android:allowNativeHeapPointerTagging=\"false\"")) { "Embedded HotSpot requires native heap pointer tagging disabled; see RuneMobile docs/dev/android.md." }
        listOf("jre/lib/server/libjvm.so", "jre/lib/libawt_xawt.so", "jre/lib/libnewscape117.so", "client.jar", "bridge.jar", "owner-plugins.jar", "owner-graphics.jar", "cacio-tta-1.18.jar", "cacio-shared-1.18.jar").forEach {
            check(file("src/main/assets/jvm/$it").isFile) { "Missing $it. Run Build-Kronos-Mobile.ps1 first." }
        }
    }
}
tasks.named("preBuild") { dependsOn("verifyKronosAssets", "verifyOwnerSigning") }
