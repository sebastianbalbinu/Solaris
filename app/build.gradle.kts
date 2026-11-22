plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun secreto(nombre: String): String = (System.getenv(nombre) ?: "").trim().replace("\"", "")

android {
    namespace = "com.sebas.solaris"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sebas.solaris"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Se llenan desde los secretos de GitHub. Si están vacíos, la app los pide al abrirse.
        buildConfigField("String", "SUPABASE_URL", "\"${secreto("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secreto("SUPABASE_ANON_KEY")}\"")
    }

    signingConfigs {
        create("logia") {
            val pw = System.getenv("KEYSTORE_PASSWORD")
            if (!pw.isNullOrBlank() && file("release.keystore").exists()) {
                storeFile = file("release.keystore")
                storePassword = pw
                keyPassword = pw
            } else {
                storeFile = file("logia.keystore")
                storePassword = "logia123"
                keyPassword = "logia123"
            }
            keyAlias = "logia"
        }
    }

    buildTypes {
        release {
            // R8 optimiza Compose y las librerías: la app arranca y se desliza más suelta
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("logia")
        }
        debug {
            signingConfig = signingConfigs.getByName("logia")
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
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("dev.chrisbanes.haze:haze:1.1.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
