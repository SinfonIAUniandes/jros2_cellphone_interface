plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jros2.cellphone_interface"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jros2.cellphone_interface"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("us.ihmc:log-tools:0.6.5")

    implementation("us.ihmc:jros2:1.1.6")
    implementation("org.bytedeco:javacpp:1.5.11:android-arm64")
    implementation("org.bytedeco:javacpp:1.5.11:android-x86_64")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Fix for XMLInputFactory not found on Android (Jackson XML / FastDDS Profiles)
    implementation("javax.xml.stream:stax-api:1.0-2")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

configurations.all {
    // log-tools mistakenly bundles log4j-core inside itself,
    // so we exclude the standalone version to prevent duplicates.
    exclude(group = "org.apache.logging.log4j", module = "log4j-core")

    // Exclude the legacy JAXB 4.0.5 modules causing the collision
    exclude(group = "com.sun.xml.bind", module = "jaxb-impl")
    exclude(group = "com.sun.xml.bind", module = "jaxb-core")
}