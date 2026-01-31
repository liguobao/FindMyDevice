plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.findmydevice.android"
    compileSdk = 34

    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val envStorePassword = System.getenv("ANDROID_STORE_PASSWORD")
    val envKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val envKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    val hasSigningConfig = listOf(
        keystorePath,
        envStorePassword,
        envKeyAlias,
        envKeyPassword
    ).all { !it.isNullOrBlank() }

    defaultConfig {
        applicationId = "com.findmydevice.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    // WebKit for modern WebView features
    implementation("androidx.webkit:webkit:1.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
