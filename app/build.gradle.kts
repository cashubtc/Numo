plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = "com.electricdreams.shellshock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.electricdreams.shellshock"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
        // We are not using Jetpack Compose in production UI; keep it disabled to
        // avoid version coupling between Compose compiler and Kotlin.
        compose = false
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        lintConfig = file("src/main/res/xml/lint.xml")
        baseline = file("lint-baseline.xml")
        abortOnError = false  // We want to build even with lint warnings
        // Also disable the specific NewApi checks for Optional
        disable += "NewApi"
    }

    useLibrary("org.apache.http.legacy")
}

dependencies {
    val lifecycleVersion = "2.7.0"
    
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")

    // AndroidX Libraries
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    
    // Project specific dependencies
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation(files("libs/cashu-java-sdk-1.0-SNAPSHOT.jar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Jackson for JSON and CBOR processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-core:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.16.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.16.1")
    
    // CBOR library from Peter O. Upokecenter
    implementation("com.upokecenter:cbor:4.5.2")

    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // QR code generation (ZXing core)
    implementation("com.google.zxing:core:3.5.3")

    // CDK Kotlin bindings
    implementation("org.cashudevkit:cdk-kotlin:0.14.1")
}
