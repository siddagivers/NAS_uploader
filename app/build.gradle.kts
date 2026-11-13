plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sk.nas.shareuploader"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.nas.shareuploader"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.2.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/NOTICE", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE.txt")
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("junit:junit:4.13.2")
}
