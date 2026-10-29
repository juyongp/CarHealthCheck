import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 출시 서명 정보는 프로젝트 밖 파일에 두고, local.properties의 release.signing에 그 경로만 적어요.
// 파일이 없으면 서명하지 않은 릴리스 APK가 만들어져요.
val releaseSigning: Properties? = Properties()
    .apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    .getProperty("release.signing")
    ?.let { file(it) }
    ?.takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "com.carhealth.checkup"
    compileSdk = 35

    defaultConfig {
        // 제출할 때 이 이름 그대로 쓰기로 했어요. 출시 후에 바꾸면 다른 앱으로 취급돼요.
        applicationId = "com.carhealth.checkup"
        // Pleos 가이드: 차량 보안 요구사항 때문에 minSdk는 28 이상이어야 해요.
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"${libs.versions.vehicleSdk.get()}\"")
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.vehicle.sdk)
    implementation(libs.tts.sdk)
    implementation(libs.stt.sdk)
    implementation(libs.kotlin.reflect)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
}
