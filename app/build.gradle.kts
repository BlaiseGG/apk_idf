plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.blaisegg.idfwidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blaisegg.idfwidget"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Clé API PRIM intégrée à la compilation depuis le secret GitHub PRIM_API_KEY
        // (jamais écrite dans le code). Vide = l'app la demande au premier lancement.
        val primKey = (System.getenv("PRIM_API_KEY") ?: "").replace("\"", "")
        buildConfigField("String", "PRIM_API_KEY", "\"$primKey\"")
    }

    // Signature fixe (sinon chaque compilation GitHub a une clé différente
    // et Android refuse d'installer la mise à jour par-dessus l'ancienne version).
    signingConfigs {
        create("fixed") {
            storeFile = file("debug.keystore")
            storePassword = "quaiquai"
            keyAlias = "quai"
            keyPassword = "quaiquai"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.all {
            it.testLogging.showStandardStreams = true
            it.environment("PRIM_API_KEY", System.getenv("PRIM_API_KEY") ?: "")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
