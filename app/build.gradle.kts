plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.gbapal.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gbapal.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("APP_VERSION_NAME") ?: "1.0.0"
    }

    // A fixed signing key so Obtainium/sideload updates install in place instead of
    // forcing an uninstall. The committed keystore is NOT secret on a public repo —
    // it exists only to keep the signature stable across CI builds for this personal
    // project. Credentials can be overridden via Gradle properties if you later swap
    // in a private keystore.
    //
    // The "unboundds" alias/password default below are NOT app branding -- they're
    // the literal identity baked into keystore/release.keystore (confirmed via
    // `keytool -list`). They're left alone deliberately: renaming the property
    // defaults without regenerating the keystore would just fail to find the
    // alias, and regenerating it would change the signing key, breaking in-place
    // Obtainium updates for anyone who already installed a build signed with the
    // current key. Cosmetic naming here isn't worth that.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(project.findProperty("releaseStoreFile") as? String ?: "keystore/release.keystore")
            storePassword = project.findProperty("releaseStorePassword") as? String ?: "unboundds-release"
            keyAlias = project.findProperty("releaseKeyAlias") as? String ?: "unboundds"
            keyPassword = project.findProperty("releaseKeyPassword") as? String ?: "unboundds-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
