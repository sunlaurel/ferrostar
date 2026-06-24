plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ktfmt)
}

android {
    namespace = "com.valhalla.valhalla"
    compileSdk = 36



    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.androidx.ktx)

    implementation(libs.moshi.kotlin)
    implementation(libs.moshi.adapters)

    // These are `api` (not `implementation`) because the model types leak through
    // Valhalla's public surface: `Valhalla.route()` takes a `RouteRequest`
    // (valhalla-models-config) and `ValhallaResponse` exposes both the Valhalla
    // (valhalla-models-api) and OSRM (osrm-api) `RouteResponse` types. Consumers
    // such as `:core` must be able to name these types to call the API.
    api(libs.valhalla.models.api)
    api(libs.valhalla.models.config)
    api(libs.osrm.api)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

// NOTE: Native libValhalla binaries (libvalhalla-wrapper.so) are pre-built and
// committed under src/main/jniLibs/. The upstream library builds them from C++
// source via build.sh; here we consume the prebuilt artifacts instead.
