import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val signingDir = rootProject.file("../subtitle-player-android-signing")
val signingPropsFile = signingDir.resolve("signing.properties")
if (!signingPropsFile.isFile) {
    throw GradleException(
        "缺少签名配置 ${signingPropsFile.path}。调试包和正式包必须使用同一把密钥，密钥放在工程目录旁边，不要提交到 git。",
    )
}
val signingProps = Properties().apply {
    signingPropsFile.inputStream().use { load(it) }
}
val signingStore = signingDir.resolve(signingProps.getProperty("storeFile").orEmpty().trim())
if (!signingStore.isFile) {
    throw GradleException("找不到签名密钥 ${signingStore.path}")
}

android {
    namespace = "com.jianqiaofan.subtitleplayer"
    compileSdk = 37

    signingConfigs {
        create("shared") {
            storeFile = signingStore
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = "com.jianqiaofan.subtitleplayer"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.datastore.preferences)
    implementation(libs.documentfile)
    implementation(libs.okhttp)

    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
