plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.junit5)
}

android {
    namespace = "it.drone.mesh"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.drone.mesh"
        minSdk = 23
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1 demo"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        checkOnly += setOf(
            "Accessibility",
            "Compliance",
            "Correctness",
            "Correctness:Chrome OS",
            "Correctness:Messages",
            "Internationalization",
            "Internationalization:Bidirectional Text",
            "Interoperability",
            "Interoperability:Kotlin Interoperability",
            "Lint Implementation Issues",
            "Performance",
            "Performance:Application Size",
            "Productivity",
            "Security",
            "Testing",
            "Usability",
            "Usability:Icons",
            "Usability:Typography",
        )
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.recyclerview)
    implementation(libs.okhttp)
    implementation(libs.tink.android)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}