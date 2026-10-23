plugins {
    id("com.android.application")
}

android {
    namespace = "pl.f650gs.forum"
    compileSdk = 37

    defaultConfig {
        applicationId = "pl.f650gs.forum"
        minSdk = 24
        targetSdk = 37
        versionCode = 45
        versionName = "0.18.2"
        // `gradle assembleDebug -Pdev` builds a side-by-side test copy ("f650gs.pl dev") that leaves the installed app alone.
        if (project.hasProperty("dev")) {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "f650gs.pl dev"
        } else {
            manifestPlaceholders["appLabel"] = "f650gs.pl"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.23.2")
    // Google sign-in for the photo album (Photos Library API); see Photos.kt.
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in local unit tests.
    testImplementation("org.json:json:20240303")
}
